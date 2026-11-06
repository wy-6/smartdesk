package com.smartdesk.ai.dto;

import com.smartdesk.ai.model.AiChatResult;

/**
 * 返回给前端的调用元信息（Week3 / Day19）。
 *
 * <p>把"花了多少 Token、耗时多少、重试了几次、traceId 是多少"暴露给调用方，
 * 是 AI 应用和普通 CRUD 后端最不一样的地方：<b>成本是业务指标的一部分</b>。
 */
public record AiMeta(String scene, String provider, String model, int promptTokens, int completionTokens,
		long latencyMs, int attempts, String traceId) {

	public static AiMeta from(AiChatResult result) {
		return new AiMeta(result.scene(), result.provider(), result.model(), result.promptTokens(),
				result.completionTokens(), result.latencyMs(), result.attempts(), result.traceId());
	}

}
