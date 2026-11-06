package com.smartdesk.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 会话发消息请求（Week4 / Day27）。
 *
 * @param message 用户消息
 */
public record SessionMessageRequest(
		@NotBlank(message = "消息不能为空") @Size(max = 2000, message = "消息不能超过 2000 字") String message) {

}
