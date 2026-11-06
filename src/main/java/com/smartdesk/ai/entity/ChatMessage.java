package com.smartdesk.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 会话消息（Week4 / Day27）。
 *
 * <p><b>{@code ownerId} 为什么冗余一份？</b>会话表里已经有了啊。
 * 因为第 2 周定下的规矩是"SQL 层再兜一层"：查消息时
 * {@code WHERE session_id=? AND owner_id=?}，即使上层的会话归属校验写漏了，
 * 也不会把别人的消息读出来。<b>冗余一列换一道防线，这笔买卖很划算。</b>
 *
 * <p><b>为什么不存完整的 tool_calls 协议字段（id/name/arguments）？</b>
 * 因为历史消息回灌给模型时，旧的 {@code tool_call_id} 无法与新请求配对，
 * 带上反而会让上游报错。所以这里存的 {@code toolCalls} 只是<b>给前端展示</b>的摘要，
 * 回灌时会被跳过。见 {@code ChatSessionService#historyFor}。
 */
@TableName("chat_message")
public class ChatMessage {

	@TableId(type = IdType.AUTO)
	private Long id;

	private Long sessionId;

	/** 归属用户，冗余存储用于 SQL 层兜底。 */
	private Long ownerId;

	/** USER / ASSISTANT / TOOL。 */
	private String role;

	private String content;

	/** 助手这一轮调用了哪些工具（JSON 摘要，仅供展示，不回灌）。 */
	private String toolCalls;

	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createdAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getSessionId() {
		return sessionId;
	}

	public void setSessionId(Long sessionId) {
		this.sessionId = sessionId;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}

	public String getRole() {
		return role;
	}

	public void setRole(String role) {
		this.role = role;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public String getToolCalls() {
		return toolCalls;
	}

	public void setToolCalls(String toolCalls) {
		this.toolCalls = toolCalls;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
