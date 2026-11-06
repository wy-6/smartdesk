package com.smartdesk.ai.service;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.entity.ChatMessage;
import com.smartdesk.ai.entity.ChatSession;
import com.smartdesk.ai.mapper.ChatMessageMapper;
import com.smartdesk.ai.mapper.ChatSessionMapper;
import com.smartdesk.common.ResourceNotFoundException;

/**
 * 会话服务（Week4 / Day27）。
 *
 * <p><b>Day27 的验收点是"多轮会话 + 用户隔离"，重点在隔离。</b>
 * 这里所有方法都强制带 {@code ownerId}，并且查询用
 * {@code WHERE id=? AND owner_id=?} 而不是"查出来再比"——
 * 和第 2 周 task 表的做法完全一致。
 *
 * <p><b>本类最需要讲清楚的一个决策：历史消息怎么回灌给模型？</b>
 *
 * <p>三个选项，选了第三个：
 * <ol>
 *   <li><b>全量回灌</b>：简单，但第 10 轮对话时光历史就上千 token，
 *       成本和延迟都不可控，还可能超出上下文窗口</li>
 *   <li><b>只回灌文本</b>（选中）：最近 N 条 user/assistant 的<b>纯文本</b>，
 *       跳过 tool 消息和 tool_calls</li>
 *   <li><b>连 tool 消息一起回灌</b>：会出错。旧的 {@code tool_call_id} 无法与
 *       新请求配对，OpenAI 协议会直接 400；就算不报错，旧工具结果也是
 *       "过期的快照"，模型会拿它当事实，产生更隐蔽的幻觉</li>
 * </ol>
 *
 * <p>代价是：模型不记得"上一轮我调过什么工具"。但这恰恰是对的 ——
 * 需要数据时它应该<b>重新查一次</b>，而不是复述五分钟前的旧结果。
 */
@Service
public class ChatSessionService {

	private static final int MAX_TITLE = 200;

	private static final int DEFAULT_TITLE_LENGTH = 30;

	private static final String DEFAULT_TITLE = "新对话";

	private final ChatSessionMapper sessionMapper;

	private final ChatMessageMapper messageMapper;

	private final AiProperties properties;

	public ChatSessionService(ChatSessionMapper sessionMapper, ChatMessageMapper messageMapper,
			AiProperties properties) {
		this.sessionMapper = sessionMapper;
		this.messageMapper = messageMapper;
		this.properties = properties;
	}

	/** 创建会话。标题为空时先占位，等第一条消息再改名。 */
	public ChatSession create(Long ownerId, String title) {
		ChatSession session = new ChatSession();
		session.setOwnerId(ownerId);
		session.setTitle(normalizeTitle(title));
		sessionMapper.insert(session);
		return session;
	}

	/** 我的会话列表，按最近活跃排序。 */
	public List<ChatSession> list(Long ownerId) {
		return sessionMapper.selectList(new LambdaQueryWrapper<ChatSession>()
				.eq(ChatSession::getOwnerId, ownerId)
				.orderByDesc(ChatSession::getUpdatedAt)
				.orderByDesc(ChatSession::getId));
	}

	/**
	 * 取会话并校验归属。
	 *
	 * <p>不存在和"是别人的"返回同一个 404 —— 和第 2 周一样，不泄露存在性。
	 */
	public ChatSession requireForOwner(Long sessionId, Long ownerId) {
		if (sessionId == null || ownerId == null) {
			throw new ResourceNotFoundException("会话不存在");
		}
		ChatSession session = sessionMapper.selectOne(new LambdaQueryWrapper<ChatSession>()
				.eq(ChatSession::getId, sessionId)
				.eq(ChatSession::getOwnerId, ownerId));
		if (session == null) {
			throw new ResourceNotFoundException("会话不存在");
		}
		return session;
	}

	/** 会话全部消息，按时间正序。 */
	public List<ChatMessage> messages(Long ownerId, Long sessionId) {
		requireForOwner(sessionId, ownerId);
		return messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
				.eq(ChatMessage::getSessionId, sessionId)
				.eq(ChatMessage::getOwnerId, ownerId)
				.orderByAsc(ChatMessage::getId));
	}

	/** 追加一条消息，并刷新会话活跃时间。 */
	public ChatMessage append(Long ownerId, Long sessionId, String role, String content, String toolCalls) {
		ChatMessage message = new ChatMessage();
		message.setSessionId(sessionId);
		message.setOwnerId(ownerId);
		message.setRole(role);
		message.setContent(content);
		message.setToolCalls(toolCalls);
		messageMapper.insert(message);

		// 只 set id：updatedAt 由 MybatisMetaObjectHandler 自动填充，
		// 在这里手写时间会和自动填充打架
		ChatSession touch = new ChatSession();
		touch.setId(sessionId);
		sessionMapper.updateById(touch);
		return message;
	}

	/** 首条消息到达时给会话起个名字，避免列表里全是"新对话"。 */
	public void renameIfDefault(Long sessionId, String firstMessage) {
		ChatSession session = sessionMapper.selectById(sessionId);
		if (session == null || !DEFAULT_TITLE.equals(session.getTitle())) {
			return;
		}
		String text = firstMessage == null ? "" : firstMessage.replaceAll("\\s+", " ").strip();
		if (text.isEmpty()) {
			return;
		}
		String title = text.length() > DEFAULT_TITLE_LENGTH ? text.substring(0, DEFAULT_TITLE_LENGTH) : text;
		ChatSession update = new ChatSession();
		update.setId(sessionId);
		update.setTitle(title);
		sessionMapper.updateById(update);
	}

	/**
	 * 构造回灌给模型的历史消息。
	 *
	 * <p>只取最近 N 条、只取 user/assistant 纯文本 —— 理由见类注释。
	 * 空内容的消息跳过：助手某轮可能只调了工具没说话，回灌一条空的 assistant
	 * 消息会让下游模型困惑。
	 */
	public List<Message> historyFor(Long ownerId, Long sessionId) {
		requireForOwner(sessionId, ownerId);
		int limit = Math.max(0, Math.min(50, properties.getAgent().getMaxHistoryMessages()));
		if (limit == 0) {
			return List.of();
		}
		List<ChatMessage> recent = messageMapper.selectList(new LambdaQueryWrapper<ChatMessage>()
				.eq(ChatMessage::getSessionId, sessionId)
				.eq(ChatMessage::getOwnerId, ownerId)
				.in(ChatMessage::getRole, List.of("USER", "ASSISTANT"))
				.orderByDesc(ChatMessage::getId)
				// limit 已被 clamp 到 [0,50]，这里不存在注入风险
				.last("LIMIT " + limit));
		// 查的是"最近 N 条"（倒序），回灌要按时间正序
		Collections.reverse(recent);

		List<Message> history = new ArrayList<>(recent.size());
		for (ChatMessage message : recent) {
			String content = message.getContent();
			if (content == null || content.isBlank()) {
				continue;
			}
			if ("USER".equals(message.getRole())) {
				history.add(new UserMessage(content));
			}
			else {
				history.add(new AssistantMessage(content));
			}
		}
		return history;
	}

	/** 删除会话（消息由外键 ON DELETE CASCADE 级联删除）。 */
	public void delete(Long ownerId, Long sessionId) {
		requireForOwner(sessionId, ownerId);
		sessionMapper.deleteById(sessionId);
	}

	private static String normalizeTitle(String title) {
		if (title == null || title.isBlank()) {
			return DEFAULT_TITLE;
		}
		String text = title.strip();
		return text.length() > MAX_TITLE ? text.substring(0, MAX_TITLE) : text;
	}

}
