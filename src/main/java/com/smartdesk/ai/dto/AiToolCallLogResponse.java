package com.smartdesk.ai.dto;

import java.time.LocalDateTime;

import com.smartdesk.ai.entity.AiToolCallLog;

/**
 * 工具调用审计响应（Week4 / Day26）。
 *
 * <p>{@code privilegeStripped} 单独暴露出来，是为了让前端/运维能一眼看到
 * "这条记录里模型试图传 userId"。正常业务里它应该永远是 false。
 */
public record AiToolCallLogResponse(Long id, String traceId, Long sessionId, String toolName, String arguments,
		boolean success, String errorCode, String resultSummary, Integer latencyMs, boolean privilegeStripped,
		LocalDateTime createdAt) {

	public static AiToolCallLogResponse from(AiToolCallLog log) {
		return new AiToolCallLogResponse(log.getId(), log.getTraceId(), log.getSessionId(), log.getToolName(),
				log.getArguments(), Boolean.TRUE.equals(log.getSuccess()), log.getErrorCode(), log.getResultSummary(),
				log.getLatencyMs(), Boolean.TRUE.equals(log.getPrivilegeStripped()), log.getCreatedAt());
	}

}
