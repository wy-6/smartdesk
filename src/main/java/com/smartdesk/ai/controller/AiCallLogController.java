package com.smartdesk.ai.controller;

import java.util.List;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdesk.ai.dto.AiCallLogResponse;
import com.smartdesk.ai.entity.AiCallLog;
import com.smartdesk.ai.service.AiCallLogService;
import com.smartdesk.common.ApiResponse;
import com.smartdesk.common.ResourceNotFoundException;
import com.smartdesk.security.CurrentUser;

/**
 * AI 调用记录查询（Week3 / Day19）。
 *
 * <p><b>这是本周新增的"用户可见的数据"，所以第 2 周的越权约定原样适用：</b>
 * <ul>
 *   <li>ownerId 只来自 JWT，接口不接受任何用户标识参数</li>
 *   <li>SQL 层带 {@code WHERE owner_id = ?}</li>
 *   <li>别人的记录一律返回 404，不返回 403 —— 不泄露"这条记录存在"</li>
 * </ul>
 *
 * <p>面试时如果被问"AI 功能和普通业务在权限上有什么不同"，标准答案是：
 * <b>没有不同。模型是新的，权限模型不是。</b>新模块最容易出的安全事故，
 * 恰恰是开发者以为"AI 接口是内部用的"而漏掉隔离。
 */
@RestController
@RequestMapping("/api/ai/calls")
public class AiCallLogController {

	private final AiCallLogService callLogService;

	public AiCallLogController(AiCallLogService callLogService) {
		this.callLogService = callLogService;
	}

	/** 分页查询自己的调用记录。 */
	@GetMapping
	public ResponseEntity<ApiResponse<List<AiCallLogResponse>>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize) {

		Long userId = requireCurrentUserId();
		List<AiCallLogResponse> records = callLogService.page(userId, page, pageSize).stream()
				.map(AiCallLogResponse::from)
				.toList();
		return ResponseEntity.ok(ApiResponse.success(records));
	}

	/** 按 id 查自己的某条记录；别人的 id 返回 404。 */
	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<AiCallLogResponse>> get(@PathVariable Long id) {
		Long userId = requireCurrentUserId();
		AiCallLog log = callLogService.findForOwner(id, userId);
		if (log == null) {
			throw new ResourceNotFoundException("调用记录不存在: id=" + id);
		}
		return ResponseEntity.ok(ApiResponse.success(AiCallLogResponse.from(log)));
	}

	private Long requireCurrentUserId() {
		Long userId = CurrentUser.getUserId();
		if (userId == null) {
			throw new ResourceNotFoundException("未登录");
		}
		return userId;
	}

}
