package com.smartdesk.ai.dto;

import java.time.LocalDateTime;

import com.smartdesk.ai.entity.AiCallLog;

/**
 * 调用记录响应（Week3 / Day19）。
 *
 * <p>和任务模块一样：<b>返回 VO 不返回实体</b>。
 * 实体将来加字段（比如内部诊断用的 trace 快照）时，不会自动泄漏给前端。
 *
 * <p>这里故意不返回 {@code ownerId}：调用方自己就是 owner，返回它没有意义，
 * 只会给"越权探测"提供可比较的信号。
 */
public record AiCallLogResponse(Long id, String traceId, String scene, String provider, String model,
		Integer promptTokens, Integer completionTokens, Integer totalTokens, Integer latencyMs, Integer attempts,
		Boolean success, String errorCode, String errorMessage, LocalDateTime createdAt) {

	public static AiCallLogResponse from(AiCallLog entity) {
		return new AiCallLogResponse(entity.getId(), entity.getTraceId(), entity.getScene(), entity.getProvider(),
				entity.getModel(), entity.getPromptTokens(), entity.getCompletionTokens(), entity.getTotalTokens(),
				entity.getLatencyMs(), entity.getAttempts(), entity.getSuccess(), entity.getErrorCode(),
				entity.getErrorMessage(), entity.getCreatedAt());
	}

}
