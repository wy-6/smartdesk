package com.smartdesk.ai.service;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.entity.AiCallLog;
import com.smartdesk.ai.mapper.AiCallLogMapper;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.model.AiScene;

/**
 * AI 调用记录服务（Week3 / Day19）。
 *
 * <p><b>关键设计：记录失败绝不能影响主流程。</b>
 * 模型调用已经成功了，如果因为"写日志失败"给用户返回 500，那是本末倒置。
 * 所以所有写操作都包在 try-catch 里，失败只打 WARN。
 *
 * <p>这类"非关键路径"的处理原则，面试可以直接讲：
 * <b>审计、统计、通知可以降级；鉴权、扣款、落库不能降级。</b>
 */
@Service
public class AiCallLogService {

	private static final Logger log = LoggerFactory.getLogger(AiCallLogService.class);

	private static final int MAX_ERROR_MESSAGE = 500;

	private final AiCallLogMapper mapper;

	private final AiProperties properties;

	public AiCallLogService(AiCallLogMapper mapper, AiProperties properties) {
		this.mapper = mapper;
		this.properties = properties;
	}

	/** 记录一次成功的调用。 */
	public void recordSuccess(Long ownerId, AiScene scene, AiChatResult result) {
		AiCallLog entity = base(ownerId, scene, result.traceId());
		entity.setModel(result.model());
		entity.setPromptTokens(result.promptTokens());
		entity.setCompletionTokens(result.completionTokens());
		entity.setTotalTokens(result.promptTokens() + result.completionTokens());
		entity.setLatencyMs((int) Math.min(Integer.MAX_VALUE, result.latencyMs()));
		entity.setAttempts(result.attempts());
		entity.setSuccess(true);
		save(entity);
	}

	/** 记录一次失败的调用。 */
	public void recordFailure(Long ownerId, AiScene scene, String traceId, AiException ex, long latencyMs) {
		AiCallLog entity = base(ownerId, scene, traceId);
		entity.setModel(properties.getModel());
		entity.setPromptTokens(0);
		entity.setCompletionTokens(0);
		entity.setTotalTokens(0);
		entity.setLatencyMs((int) Math.min(Integer.MAX_VALUE, latencyMs));
		entity.setAttempts(ex.getAttempts());
		entity.setSuccess(false);
		entity.setErrorCode(ex.getCode().getCode());
		entity.setErrorMessage(sanitize(ex.getMessage()));
		save(entity);
	}

	/**
	 * 分页查询当前用户的调用记录。
	 *
	 * <p>排序用 {@code created_at DESC, id DESC}：同一秒内插入的多条记录，
	 * 只按时间排序结果不稳定（分页会重复/漏数据），加 id 才是确定序。
	 */
	public List<AiCallLog> page(Long ownerId, int pageNum, int pageSize) {
		Page<AiCallLog> page = new Page<>(Math.max(1, pageNum), Math.min(100, Math.max(1, pageSize)));
		LambdaQueryWrapper<AiCallLog> query = new LambdaQueryWrapper<AiCallLog>()
				.eq(AiCallLog::getOwnerId, ownerId)
				.orderByDesc(AiCallLog::getCreatedAt)
				.orderByDesc(AiCallLog::getId);
		return mapper.selectPage(page, query).getRecords();
	}

	/** 按 id 查，强制带 ownerId —— 别人的记录查出来就是 null。 */
	public AiCallLog findForOwner(Long id, Long ownerId) {
		if (id == null || ownerId == null) {
			return null;
		}
		return mapper.selectOne(new LambdaQueryWrapper<AiCallLog>()
				.eq(AiCallLog::getId, id)
				.eq(AiCallLog::getOwnerId, ownerId));
	}

	private AiCallLog base(Long ownerId, AiScene scene, String traceId) {
		AiCallLog entity = new AiCallLog();
		entity.setOwnerId(ownerId);
		entity.setTraceId(traceId);
		entity.setScene(scene.getCode());
		entity.setProvider(properties.getProvider());
		return entity;
	}

	private void save(AiCallLog entity) {
		try {
			mapper.insert(entity);
		}
		catch (RuntimeException ex) {
			// 记录失败不影响业务，但必须留痕，否则"成本报表少数据"永远查不出来
			log.warn("写入 AI 调用记录失败 traceId={} scene={}: {}", entity.getTraceId(), entity.getScene(),
					ex.getMessage());
		}
	}

	/** 错误信息脱敏：去换行（防止日志注入）、截断（防止超列宽）。 */
	private static String sanitize(String message) {
		if (message == null) {
			return null;
		}
		String singleLine = message.replaceAll("[\\r\\n]+", " ").strip();
		return singleLine.length() > MAX_ERROR_MESSAGE
				? singleLine.substring(0, MAX_ERROR_MESSAGE) + "…"
				: singleLine;
	}

}
