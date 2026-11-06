package com.smartdesk.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 自由问答请求（Week3 / Day16）。
 *
 * <p>{@code @Size(max = 2000)} 不只是"防超长请求"：<b>Prompt 的每个字符都要付钱</b>，
 * 而且超长输入会挤占上下文窗口，让模型忘掉系统提示词里的约束。
 * 长度限制是成本控制和效果控制的同一个动作。
 */
public record ChatRequest(
		@NotBlank(message = "message 不能为空")
		@Size(max = 2000, message = "message 不能超过 2000 字") String message) {
}
