package com.smartdesk.ai.controller;

import java.util.List;
import java.util.Map;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdesk.ai.dto.AgentChatRequest;
import com.smartdesk.ai.dto.AgentResult;
import com.smartdesk.ai.dto.AiToolCallLogResponse;
import com.smartdesk.ai.dto.ToolDescriptor;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.service.AgentService;
import com.smartdesk.ai.service.ToolExecutionService;
import com.smartdesk.ai.tool.ToolRegistry;
import com.smartdesk.common.ApiResponse;
import com.smartdesk.security.CurrentUser;
import com.smartdesk.task.dto.TaskResponse;

import jakarta.validation.Valid;

/**
 * Agent 接口（Week4 / Day22-26）。
 *
 * <p><b>五个接口围绕一条主线：模型提议 → 人确认 → 数据落地。</b>
 * <pre>
 * POST   /api/ai/agent                          无状态对话（带工具）
 * GET    /api/ai/tools                          我有哪些工具（含 schema）
 * POST   /api/ai/confirmations/{id}/confirm     确认草稿 → 真正创建
 * DELETE /api/ai/confirmations/{id}             放弃草稿
 * GET    /api/ai/tool-calls                     工具调用审计
 * </pre>
 *
 * <p><b>注意 {@code /confirm} 是一个普通 POST，不是"让模型再确认一次"。</b>
 * 这是本周最重要的设计：确认必须是<b>确定性的 HTTP 调用</b>，
 * 由用户在界面上点按钮触发。如果靠 Prompt 让模型问"你确定吗"，
 * 模型完全可以自问自答，那道防线等于不存在。
 */
@RestController
@RequestMapping("/api/ai")
public class AgentController {

	private final AgentService agentService;

	private final ToolRegistry toolRegistry;

	private final ToolExecutionService toolExecutions;

	public AgentController(AgentService agentService, ToolRegistry toolRegistry,
			ToolExecutionService toolExecutions) {
		this.agentService = agentService;
		this.toolRegistry = toolRegistry;
		this.toolExecutions = toolExecutions;
	}

	/** 无状态对话：一次请求一次回答，不带历史。 */
	@PostMapping("/agent")
	public ResponseEntity<ApiResponse<AgentResult>> agent(@Valid @RequestBody AgentChatRequest request) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(agentService.chat(userId, request.message())));
	}

	/**
	 * 列出已注册工具。
	 *
	 * <p>返回的 schema 就是发给模型的那一份，包含 {@code additionalProperties: false}。
	 * 可以用它来验证"userId 没有出现在参数里"——这比读代码更直观。
	 */
	@GetMapping("/tools")
	public ResponseEntity<ApiResponse<List<ToolDescriptor>>> tools() {
		requireCurrentUserId();
		List<ToolDescriptor> tools = toolRegistry.all().stream().map(ToolDescriptor::from).toList();
		return ResponseEntity.ok(ApiResponse.success(tools));
	}

	/** 确认草稿并创建任务。重复确认返回 404，不会创建第二个。 */
	@PostMapping("/confirmations/{confirmationId}/confirm")
	public ResponseEntity<ApiResponse<TaskResponse>> confirm(@PathVariable String confirmationId) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(TaskResponse.from(agentService.confirm(userId, confirmationId))));
	}

	/** 放弃草稿。 */
	@DeleteMapping("/confirmations/{confirmationId}")
	public ResponseEntity<ApiResponse<Map<String, Object>>> cancel(@PathVariable String confirmationId) {
		Long userId = requireCurrentUserId();
		boolean cancelled = agentService.cancel(userId, confirmationId);
		return ResponseEntity.ok(ApiResponse.success(Map.of("cancelled", cancelled)));
	}

	/** 工具调用审计，只返回当前用户的记录。 */
	@GetMapping("/tool-calls")
	public ResponseEntity<ApiResponse<List<AiToolCallLogResponse>>> toolCalls(
			@RequestParam(defaultValue = "1") int page, @RequestParam(defaultValue = "20") int pageSize) {
		Long userId = requireCurrentUserId();
		List<AiToolCallLogResponse> logs = toolExecutions.page(userId, page, pageSize)
				.stream()
				.map(AiToolCallLogResponse::from)
				.toList();
		return ResponseEntity.ok(ApiResponse.success(logs));
	}

	private Long requireCurrentUserId() {
		Long userId = CurrentUser.getUserId();
		if (userId == null) {
			// 防御性编程：SecurityConfig 已保证 /api/ai/** 需要认证
			throw new AiException(AiErrorCode.INVALID_REQUEST, "未登录");
		}
		return userId;
	}

}
