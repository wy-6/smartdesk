package com.smartdesk.ai.service;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.stereotype.Service;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.PendingConfirmation;
import com.smartdesk.common.BusinessException;
import com.smartdesk.common.ResourceNotFoundException;

import tools.jackson.databind.ObjectMapper;

/**
 * 写操作确认服务（Week4 / Day25）。
 *
 * <p><b>这是本项目在 Agent 安全上最重要的一块。</b>
 * 模型可以提议创建任务，但"真的创建"必须由一次确定性的 HTTP 调用触发。
 * 中间用一个短命的 Redis 键承载草稿。
 *
 * <p><b>为什么用 Redis 而不是数据库？</b>
 * <ul>
 *   <li>草稿是<b>临时</b>的：5 分钟不确认就该消失，数据库要额外写清理任务</li>
 *   <li>TTL 天然解决"确认链接泄漏后长期有效"的问题</li>
 *   <li>读写一次就删，不需要事务、不需要历史</li>
 * </ul>
 * 如果确认记录需要长期审计，应该另建表存"确认结果"，而不是把草稿留在库里。
 *
 * <p><b>key 里为什么带 ownerId？</b>
 * 两条理由，一条是安全，一条是性能：
 * <ol>
 *   <li><b>安全</b>：别人拿到 confirmationId 也拼不出同一个 key，越权确认直接变成"不存在"</li>
 *   <li><b>性能/可运维</b>：按用户前缀可以批量清理、可以按用户排查</li>
 * </ol>
 * 底层的铁律和第 2 周一样：<b>数据边界只来自登录态</b>。
 */
@Service
public class ConfirmationService {

	private static final Logger log = LoggerFactory.getLogger(ConfirmationService.class);

	private static final String KEY_PREFIX = "smartdesk:ai:confirm";

	/**
	 * 原子地"取出并删除"。
	 *
	 * <p>为什么不能 {@code get()} 之后再 {@code delete()}？
	 * 两个并发的确认请求（用户双击了按钮）会同时读到草稿，然后都去插入任务 ——
	 * <b>一条草稿变成两个任务</b>。这不是理论问题，是前端按钮防抖失效时的常态。
	 *
	 * <p>为什么用 Lua 而不是 {@code GETDEL}？
	 * {@code GETDEL} 需要 Redis 6.2+，本项目开发环境是 5.0.14。
	 * 用 Lua 脚本则没有版本限制，而且 GET+DEL 在脚本里是原子执行的。
	 */
	private static final DefaultRedisScript<String> TAKE_SCRIPT = new DefaultRedisScript<>("""
			local value = redis.call('GET', KEYS[1])
			if value then
			  redis.call('DEL', KEYS[1])
			end
			return value
			""", String.class);

	private final StringRedisTemplate redis;

	private final ObjectMapper objectMapper;

	private final AiProperties properties;

	public ConfirmationService(StringRedisTemplate redis, ObjectMapper objectMapper, AiProperties properties) {
		this.redis = redis;
		this.objectMapper = objectMapper;
		this.properties = properties;
	}

	/**
	 * 生成一份待确认草稿。
	 *
	 * @return 含 confirmationId 的草稿
	 */
	public PendingConfirmation create(Long ownerId, Long sessionId, String toolName, String title, String description,
			Integer priority, String dueDateIso) {
		String confirmationId = UUID.randomUUID().toString().replace("-", "").substring(0, 24);
		PendingConfirmation pending = new PendingConfirmation(confirmationId, ownerId, sessionId, toolName, title,
				description, priority, dueDateIso, System.currentTimeMillis());
		Duration ttl = properties.getAgent().getConfirmationTtl();
		try {
			redis.opsForValue().set(key(ownerId, confirmationId), objectMapper.writeValueAsString(pending), ttl);
		}
		catch (RuntimeException ex) {
			// 确认服务不可用时，宁可不创建，也不能"绕过确认直接落库"
			log.error("写入待确认草稿失败 ownerId={} tool={}", ownerId, toolName, ex);
			throw new BusinessException("CONFIRMATION_UNAVAILABLE", "确认服务暂不可用，请稍后重试");
		}
		log.info("生成待确认草稿 ownerId={} tool={} confirmationId={} ttl={}s", ownerId, toolName, confirmationId,
				ttl.toSeconds());
		return pending;
	}

	/**
	 * 取出并作废一份草稿（确认时调用）。
	 *
	 * <p>取不到就抛 404：可能是过期了，也可能是别人的确认号。
	 * <b>两种情况返回同一个结果</b>，不泄露"这个确认号是否存在"。
	 */
	public PendingConfirmation take(Long ownerId, String confirmationId) {
		String json;
		try {
			json = redis.execute(TAKE_SCRIPT, List.of(key(ownerId, confirmationId)));
		}
		catch (RuntimeException ex) {
			log.error("读取待确认草稿失败 ownerId={} confirmationId={}", ownerId, confirmationId, ex);
			throw new BusinessException("CONFIRMATION_UNAVAILABLE", "确认服务暂不可用，请稍后重试");
		}
		if (json == null) {
			throw new ResourceNotFoundException("确认信息不存在或已过期");
		}
		try {
			return objectMapper.readValue(json, PendingConfirmation.class);
		}
		catch (RuntimeException ex) {
			log.error("待确认草稿反序列化失败 confirmationId={}", confirmationId, ex);
			throw new BusinessException("CONFIRMATION_CORRUPTED", "确认信息已损坏，请重新发起");
		}
	}

	/** 取消一份草稿。返回是否真的删掉了（过期或不存在都返回 false）。 */
	public boolean cancel(Long ownerId, String confirmationId) {
		Boolean deleted = redis.delete(key(ownerId, confirmationId));
		return Boolean.TRUE.equals(deleted);
	}

	/** 仅查看，不作废 —— 前端刷新确认页时用。 */
	public Optional<PendingConfirmation> peek(Long ownerId, String confirmationId) {
		String json = redis.opsForValue().get(key(ownerId, confirmationId));
		if (json == null) {
			return Optional.empty();
		}
		try {
			return Optional.of(objectMapper.readValue(json, PendingConfirmation.class));
		}
		catch (RuntimeException ex) {
			return Optional.empty();
		}
	}

	private String key(Long ownerId, String confirmationId) {
		return KEY_PREFIX + ":" + ownerId + ":" + confirmationId;
	}

}
