package com.smartdesk.ai.model;

/**
 * 一次模型调用的结果（Week3 / Day16）。
 *
 * <p>为什么返回 record 而不是直接返回 {@code String}？
 * 因为调用方（Controller）需要知道<b>这次回答的成本和健康度</b>：
 * 用了哪个模型、花了多少 Token、耗时多少、重试了几次。
 * 只返回字符串的话，这些信息只能从日志里翻，前端和排障都拿不到。
 *
 * @param content 模型输出的文本
 * @param scene 场景标签
 * @param provider 提供方（mock / openai）
 * @param model 模型名
 * @param promptTokens 输入 Token 数
 * @param completionTokens 输出 Token 数
 * @param latencyMs 端到端耗时（含重试）
 * @param attempts 实际尝试次数
 * @param traceId 调用追踪号，前端报障时提供它即可在日志/调用记录里定位
 */
public record AiChatResult(String content, String scene, String provider, String model,
		int promptTokens, int completionTokens, long latencyMs, int attempts, String traceId) {

	/** 总 Token；上游没返回时用 0 兜底，避免前端拿到 null。 */
	public int totalTokens() {
		return promptTokens + completionTokens;
	}

}
