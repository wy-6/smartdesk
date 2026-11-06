package com.smartdesk.ai.controller;

import java.io.IOException;
import java.util.Map;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

import com.smartdesk.ai.dto.ChatAnswer;
import com.smartdesk.ai.dto.ChatRequest;
import com.smartdesk.ai.dto.ClassifyRequest;
import com.smartdesk.ai.dto.ClassifyResponse;
import com.smartdesk.ai.dto.SummaryRequest;
import com.smartdesk.ai.dto.SummaryResponse;
import com.smartdesk.ai.dto.TaskDraftRequest;
import com.smartdesk.ai.dto.TaskDraftResponse;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.service.AiService;
import com.smartdesk.common.ApiResponse;
import com.smartdesk.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * AI 接口（Week3 / Day16-20）。
 *
 * <p><b>Controller 的职责被严格限制在三件事：</b>取登录态 → 调 Service → 包响应。
 * 这里看不到任何 Prompt、任何 HTTP 调用、任何 JSON 解析 ——
 * 那是 Service 的活。这个边界和第 1 周给任务模块定的规矩完全一致。
 *
 * <p><b>Day20：SSE 流式响应的关键点</b>
 * <ol>
 *   <li>返回 {@link SseEmitter}，HTTP 响应头是 {@code text/event-stream}，
 *       连接保持打开，服务端可以持续 push</li>
 *   <li><b>SecurityContext 在异步线程里是空的</b>：所以必须在进入异步<b>之前</b>
 *       把 userId 取出来，不能等到回调里再调 {@code CurrentUser.getUserId()}</li>
 *   <li>流一旦开始，HTTP 状态码就发出去了，<b>后面出错只能发 error 事件</b>，
 *       不能再返回 500。这是 SSE 和普通接口最大的区别，也是最容易踩的坑</li>
 *   <li>必须设超时并处理客户端断开，否则连接会一直挂着占线程</li>
 * </ol>
 */
@RestController
@RequestMapping("/api/ai")
public class AiController {

	/** SSE 连接最长存活时间，超过就强制关闭。 */
	private static final long SSE_TIMEOUT_MILLIS = 60_000L;

	private final AiService aiService;

	public AiController(AiService aiService) {
		this.aiService = aiService;
	}

	/** 自由问答（非流式）。 */
	@PostMapping("/chat")
	public ResponseEntity<ApiResponse<ChatAnswer>> chat(@Valid @RequestBody ChatRequest request) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(aiService.chat(userId, request.message())));
	}

	/** 摘要。 */
	@PostMapping("/summarize")
	public ResponseEntity<ApiResponse<SummaryResponse>> summarize(@Valid @RequestBody SummaryRequest request) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(
				aiService.summarize(userId, request.text(), request.maxChars())));
	}

	/** 分类。 */
	@PostMapping("/classify")
	public ResponseEntity<ApiResponse<ClassifyResponse>> classify(@Valid @RequestBody ClassifyRequest request) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(aiService.classify(userId, request.text())));
	}

	/**
	 * 自然语言 → 任务草稿。
	 *
	 * <p><b>返回 200 而不是 201</b>：草稿没有创建任何资源。
	 * 第 4 周会加"确认后创建"的接口，那个才返回 201。
	 */
	@PostMapping("/task-draft")
	public ResponseEntity<ApiResponse<TaskDraftResponse>> taskDraft(@Valid @RequestBody TaskDraftRequest request) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(aiService.draftTask(userId, request.text())));
	}

	/**
	 * 流式问答（SSE）。
	 *
	 * <p>事件协议：
	 * <pre>
	 * event: delta    data: {"text":"增量片段"}
	 * event: done     data: {"content":"完整回答","meta":{...}}
	 * event: error    data: {"code":"AI_TIMEOUT","message":"..."}
	 * </pre>
	 */
	@PostMapping(value = "/chat/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
	public SseEmitter stream(@Valid @RequestBody ChatRequest request) {
		// 异步线程里拿不到 SecurityContext，必须在这里取
		Long userId = requireCurrentUserId();
		SseEmitter emitter = new SseEmitter(SSE_TIMEOUT_MILLIS);

		emitter.onTimeout(() -> {
			// 超时不抛异常，避免容器再走一遍异常处理（此时响应已提交）
			emitter.complete();
		});
		emitter.onError(throwable -> emitter.complete());

		aiService.streamChat(userId, request.message(),
				delta -> send(emitter, "delta", Map.of("text", delta)),
				result -> {
					send(emitter, "done", Map.of("content", result.content(), "meta", meta(result)));
					emitter.complete();
				},
				error -> {
					send(emitter, "error", Map.of(
							"code", error.getCode().getCode(),
							"message", error.getMessage() == null ? "" : error.getMessage()));
					emitter.complete();
				});
		return emitter;
	}

	private void send(SseEmitter emitter, String event, Object payload) {
		try {
			emitter.send(SseEmitter.event().name(event).data(payload, MediaType.APPLICATION_JSON));
		}
		catch (IOException | IllegalStateException ex) {
			// 客户端提前断开是正常现象（刷新页面、切走），只记一笔，不当作故障
			emitter.completeWithError(ex);
		}
	}

	private Map<String, Object> meta(AiChatResult result) {
		return Map.of(
				"scene", result.scene(),
				"provider", result.provider(),
				"model", result.model(),
				"promptTokens", result.promptTokens(),
				"completionTokens", result.completionTokens(),
				"latencyMs", result.latencyMs(),
				"attempts", result.attempts(),
				"traceId", result.traceId());
	}

	private Long requireCurrentUserId() {
		Long userId = CurrentUser.getUserId();
		if (userId == null) {
			// 防御性编程：SecurityConfig 已经保证 /api/ai/** 需要认证
			throw new AiException(AiErrorCode.INVALID_REQUEST, "未登录");
		}
		return userId;
	}

}
