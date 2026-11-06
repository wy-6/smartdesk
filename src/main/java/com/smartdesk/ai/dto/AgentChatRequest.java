package com.smartdesk.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 无状态 Agent 请求（Week4）。
 *
 * @param message 用户消息。上限 2000 字 —— 再长基本是粘贴文档，
 *                应该走摘要接口而不是对话接口，否则一次请求的成本会失控
 */
public record AgentChatRequest(@NotBlank(message = "消息不能为空") @Size(max = 2000, message = "消息不能超过 2000 字") String message) {

}
