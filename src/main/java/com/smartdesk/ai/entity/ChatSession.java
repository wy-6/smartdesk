package com.smartdesk.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 对话会话（Week4 / Day27）。
 *
 * <p><b>为什么 Agent 需要会话，而第 3 周的 chat 不需要？</b>
 * 因为带工具的对话天生是多轮的：用户说"帮我看看逾期任务"，接着说
 * "把第二个创建成任务" —— 第二句话里的"第二个"只有在历史里才有意义。
 * 没有会话，模型每次都在真空里回答。
 *
 * <p><b>会话表只存元信息，不存消息。</b>消息在 {@link ChatMessage} 里，
 * 拆分的原因很实际：会话列表页只需要标题和时间，如果标题和消息混在一张表，
 * 每次翻列表都要扫过几万条消息文本。
 */
@TableName("chat_session")
public class ChatSession {

	@TableId(type = IdType.AUTO)
	private Long id;

	/** 归属用户，来自 JWT。 */
	private Long ownerId;

	/** 会话标题，默认取第一条用户消息的前 30 字。 */
	private String title;

	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createdAt;

	@TableField(fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
