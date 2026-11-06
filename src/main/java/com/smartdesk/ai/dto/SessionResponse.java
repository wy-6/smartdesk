package com.smartdesk.ai.dto;

import java.time.LocalDateTime;

import com.smartdesk.ai.entity.ChatSession;

/**
 * 会话响应（Week4 / Day27）。
 *
 * <p>不含 {@code ownerId}：前端不需要知道自己的 id，返回它只会增加
 * "前端传 id 回来"的诱惑，而那是我们要坚决杜绝的模式。
 */
public record SessionResponse(Long id, String title, LocalDateTime createdAt, LocalDateTime updatedAt) {

	public static SessionResponse from(ChatSession session) {
		return new SessionResponse(session.getId(), session.getTitle(), session.getCreatedAt(), session.getUpdatedAt());
	}

}
