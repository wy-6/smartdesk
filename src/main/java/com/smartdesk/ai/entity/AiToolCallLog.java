package com.smartdesk.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 工具调用审计记录（Week4 / Day26）。
 *
 * <p><b>为什么模型调用日志（{@code ai_call_log}）不够，还要单独一张表？</b>
 * 一次 Agent 请求是 1 次业务操作，但它可能包含 3 次模型调用和 4 次工具执行。
 * 两者的排查价值完全不同：
 * <ul>
 *   <li>{@code ai_call_log} 回答"这次对话花了多少 token、模型有没有超时"</li>
 *   <li>{@code ai_tool_call_log} 回答"模型到底动了哪些数据、参数是什么、失败了没有"</li>
 * </ul>
 * 第二条在出事时更重要。用户说"AI 把我的任务改了"，你必须能查到
 * 当时调了哪个工具、传了什么参数。<b>没有工具级审计的 Agent 是不可上线的。</b>
 *
 * <p><b>参数要存，但要脱敏 + 截断</b>：工具参数是模型的自由输出，可能很长、
 * 可能包含用户粘贴的敏感内容。存前 2000 字符用于复现问题就够了，
 * 不做全量存储（那是数据合规风险，不是功能）。
 */
@TableName("ai_tool_call_log")
public class AiToolCallLog {

	@TableId(type = IdType.AUTO)
	private Long id;

	/** 归属用户，来自 JWT。 */
	private Long ownerId;

	/** 与 ai_call_log.trace_id 对应，能把一次对话的模型调用和工具调用串起来。 */
	private String traceId;

	/** 会话 id，无状态调用为 null。 */
	private Long sessionId;

	/** 工具名。 */
	private String toolName;

	/** 调用参数（JSON 字符串，已截断）。 */
	private String arguments;

	/** 1 成功 0 失败。 */
	private Boolean success;

	/** 失败时的错误码，如 TOOL_ARGUMENT_INVALID。 */
	private String errorCode;

	/** 结果摘要（已脱敏截断），用于快速判断"返回了什么"。 */
	private String resultSummary;

	private Integer latencyMs;

	/** 是否发生过越权参数剥离 —— 安全审计的关键字段。 */
	private Boolean privilegeStripped;

	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createdAt;

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

	public String getTraceId() {
		return traceId;
	}

	public void setTraceId(String traceId) {
		this.traceId = traceId;
	}

	public Long getSessionId() {
		return sessionId;
	}

	public void setSessionId(Long sessionId) {
		this.sessionId = sessionId;
	}

	public String getToolName() {
		return toolName;
	}

	public void setToolName(String toolName) {
		this.toolName = toolName;
	}

	public String getArguments() {
		return arguments;
	}

	public void setArguments(String arguments) {
		this.arguments = arguments;
	}

	public Boolean getSuccess() {
		return success;
	}

	public void setSuccess(Boolean success) {
		this.success = success;
	}

	public String getErrorCode() {
		return errorCode;
	}

	public void setErrorCode(String errorCode) {
		this.errorCode = errorCode;
	}

	public String getResultSummary() {
		return resultSummary;
	}

	public void setResultSummary(String resultSummary) {
		this.resultSummary = resultSummary;
	}

	public Integer getLatencyMs() {
		return latencyMs;
	}

	public void setLatencyMs(Integer latencyMs) {
		this.latencyMs = latencyMs;
	}

	public Boolean getPrivilegeStripped() {
		return privilegeStripped;
	}

	public void setPrivilegeStripped(Boolean privilegeStripped) {
		this.privilegeStripped = privilegeStripped;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
