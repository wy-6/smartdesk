package com.smartdesk.ai.dto;

/**
 * 摘要响应。
 *
 * @param summary 摘要正文
 * @param maxChars 请求的字数上限（回显方便前端核对）
 * @param meta 调用元信息
 */
public record SummaryResponse(String summary, int maxChars, AiMeta meta) {
}
