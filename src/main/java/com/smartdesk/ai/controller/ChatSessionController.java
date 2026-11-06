package com.smartdesk.ai.controller;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.Message;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdesk.ai.dto.AgentResult;
import com.smartdesk.ai.dto.MessageResponse;
import com.smartdesk.ai.dto.SessionCreateRequest;
import com.smartdesk.ai.dto.SessionMessageRequest;
import com.smartdesk.ai.dto.SessionResponse;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.service.AgentLoop;
import com.smartdesk.ai.service.ChatSessionService;
import com.smartdesk.common.ApiResponse;
import com.smartdesk.security.CurrentUser;

import jakarta.validation.Valid;
import tools.jackson.databind.ObjectMapper;

/**
 * 多轮会话接口（Week4 / Day27）。
 *
 * <p><b>为什么"发消息"不是简单的"存一条 + 调模型"？</b>
 * 顺序很重要：
 * <pre>
 * 1. 校验会话归属（别人的会话直接 404）
 * 2. 取历史（只取最近 N 条纯文本）
 * 3. 先把用户消息落库 —— 即使后面模型失败，用户也能看到自己问了什么
 * 4. 跑 Agent
 * 5. 把助手回答落库（带工具调用摘要）
 * </pre>
 * 第 3 步的位置是刻意的：如果等模型成功才落库，一次超时就会让用户的输入凭空消失，
 * 这是最令人恼火的体验之一。<b>用户输入先落地，模型回答后补。</b>
 *
 * <p>代价是失败重试会产生重复的 user 消息。可以用客户端消息 id 做幂等，
 * 但那需要前端配合，本项目暂不引入（写进遗留项）。
 */
@RestController
@RequestMapping("/api/ai/sessions")
public class ChatSessionController {

	private static final Logger log = LoggerFactory.getLogger(ChatSessionController.class);

	private final ChatSessionService sessions;

	private final AgentLoop agentLoop;

	private final ObjectMapper objectMapper;

	public ChatSessionController(ChatSessionService sessions, AgentLoop agentLoop, ObjectMapper objectMapper) {
		this.sessions = sessions;
		this.agentLoop = agentLoop;
		this.objectMapper = objectMapper;
	}

	/** 创建会话。 */
	@PostMapping
	public ResponseEntity<ApiResponse<SessionResponse>> create(
			@Valid @RequestBody(required = false) SessionCreateRequest request) {
		Long userId = requireCurrentUserId();
		String title = request == null ? null : request.title();
		return ResponseEntity.ok(ApiResponse.success(SessionResponse.from(sessions.create(userId, title))));
	}

	/** 我的会话列表。 */
	@GetMapping
	public ResponseEntity<ApiResponse<List<SessionResponse>>> list() {
		Long userId = requireCurrentUserId();
		List<SessionResponse> list = sessions.list(userId).stream().map(SessionResponse::from).toList();
		return ResponseEntity.ok(ApiResponse.success(list));
	}

	/** 会话的全部消息。别人的会话返回 404。 */
	@GetMapping("/{sessionId}/messages")
	public ResponseEntity<ApiResponse<List<MessageResponse>>> messages(@PathVariable Long sessionId) {
		Long userId = requireCurrentUserId();
		List<MessageResponse> list = sessions.messages(userId, sessionId).stream().map(MessageResponse::from).toList();
		return ResponseEntity.ok(ApiResponse.success(list));
	}

	/** 发一条消息，触发 Agent 多轮工具调用。 */
	@PostMapping("/{sessionId}/messages")
	public ResponseEntity<ApiResponse<AgentResult>> send(@PathVariable Long sessionId,
			@Valid @RequestBody SessionMessageRequest request) {
		Long userId = requireCurrentUserId();
		// 先校验归属，再取历史 —— 顺序不能反，否则会先读到别人的历史
		sessions.requireForOwner(sessionId, userId);
		List<Message> history = sessions.historyFor(userId, sessionId);

		sessions.append(userId, sessionId, "USER", request.message(), null);
		sessions.renameIfDefault(sessionId, request.message());

		AgentResult result = agentLoop.run(userId, sessionId, request.message(), history);

		sessions.append(userId, sessionId, "ASSISTANT", result.answer(), serializeTraces(result));
		return ResponseEntity.ok(ApiResponse.success(result));
	}

	/** 删除会话（消息级联删除）。 */
	@DeleteMapping("/{sessionId}")
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long sessionId) {
		Long userId = requireCurrentUserId();
		sessions.delete(userId, sessionId);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	/**
	 * 把工具调用轨迹序列化后存进消息表。
	 *
	 * <p>序列化失败不抛异常：这只是"给前端展示用的摘要"，
	 * 存不进去不该让整轮对话失败。<b>非关键路径可以降级。</b>
	 */
	private String serializeTraces(AgentResult result) {
		if (result.toolCalls() == null || result.toolCalls().isEmpty()) {
			return null;
		}
		try {
			return objectMapper.writeValueAsString(result.toolCalls());
		}
		catch (RuntimeException ex) {
			log.warn("工具调用轨迹序列化失败 traceId={}: {}", result.traceId(), ex.getMessage());
			return null;
		}
	}

	private Long requireCurrentUserId() {
		Long userId = CurrentUser.getUserId();
		if (userId == null) {
			throw new AiException(AiErrorCode.INVALID_REQUEST, "未登录");
		}
		return userId;
	}

}
