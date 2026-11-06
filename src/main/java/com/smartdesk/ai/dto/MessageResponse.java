package com.smartdesk.ai.dto;

import java.time.LocalDateTime;

import com.smartdesk.ai.entity.ChatMessage;

/**
 * 会话消息响应（Week4 / Day27）。
 *
 * @param toolCalls 该条助手消息调用过的工具摘要（JSON 字符串），前端可折叠展示
 */
public record MessageResponse(Long id, String role, String content, String toolCalls, LocalDateTime createdAt) {

	public static MessageResponse from(ChatMessage message) {
		return new MessageResponse(message.getId(), message.getRole(), message.getContent(), message.getToolCalls(),
				message.getCreatedAt());
	}

}
