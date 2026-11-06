package com.smartdesk.ai.dto;

/**
 * 分类响应。
 *
 * <p>{@code category} 一定是 {@code TaskCategory} 的枚举名，
 * 绝不把模型原始字符串直接透出 —— 这是 Day17 的核心验收点。
 *
 * @param category 分类代码
 * @param reason 模型给出的判断依据
 * @param meta 调用元信息
 */
public record ClassifyResponse(String category, String reason, AiMeta meta) {
}
