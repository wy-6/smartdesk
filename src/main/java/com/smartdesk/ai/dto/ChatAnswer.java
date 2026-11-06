package com.smartdesk.ai.dto;

/**
 * 自由问答响应。
 *
 * @param answer 模型回答
 * @param meta 调用元信息
 */
public record ChatAnswer(String answer, AiMeta meta) {
}
