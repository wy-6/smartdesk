package com.smartdesk.ai.entity;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * AI 调用记录（Week3 / Day19）。
 *
 * <p><b>为什么必须把调用记录下来，而不是只打日志？</b>
 * <ul>
 *   <li><b>成本可算</b>：按 owner 聚合 Token，才知道谁在烧钱、哪个功能最贵</li>
 *   <li><b>问题可查</b>：用户报障时给出 traceId，一条 SQL 就能还原当时的模型、耗时、错误码</li>
 *   <li><b>日志会滚掉，数据库不会</b>：日志平台通常只保留 7~30 天，成本报表要留更久</li>
 * </ul>
 *
 * <p><b>安全边界同第 2 周：</b>{@code owner_id} 只能来自登录态，
 * 查询强制 {@code WHERE owner_id = ?}，别人的调用记录查出来就是 404。
 *
 * <p><b>注意：这里不存 Prompt 全文和模型回答全文。</b>
 * 业务文本可能含手机号、身份证、客户名单，写进调用日志等于把敏感数据复制了一份。
 * 只存"元数据"（长度、Token、耗时、错误码）才是合规做法。
 */
@TableName("ai_call_log")
public class AiCallLog {

	@TableId(type = IdType.AUTO)
	private Long id;

	/** 归属用户，来自 JWT，客户端不可指定。 */
	private Long ownerId;

	/** 调用追踪号，前端报障时提供。 */
	private String traceId;

	/** 场景：chat / summary / classify / task_draft。 */
	private String scene;

	/** 提供方：mock / openai。 */
	private String provider;

	/** 模型名。 */
	private String model;

	private Integer promptTokens;

	private Integer completionTokens;

	private Integer totalTokens;

	private Integer latencyMs;

	/** 实际尝试次数（含重试）。 */
	private Integer attempts;

	/** 1 成功 0 失败。 */
	private Boolean success;

	/** 失败时的错误码，成功为 null。 */
	private String errorCode;

	/** 失败原因，已截断，不含敏感内容。 */
	private String errorMessage;

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

	public String getScene() {
		return scene;
	}

	public void setScene(String scene) {
		this.scene = scene;
	}

	public String getProvider() {
		return provider;
	}

	public void setProvider(String provider) {
		this.provider = provider;
	}

	public String getModel() {
		return model;
	}

	public void setModel(String model) {
		this.model = model;
	}

	public Integer getPromptTokens() {
		return promptTokens;
	}

	public void setPromptTokens(Integer promptTokens) {
		this.promptTokens = promptTokens;
	}

	public Integer getCompletionTokens() {
		return completionTokens;
	}

	public void setCompletionTokens(Integer completionTokens) {
		this.completionTokens = completionTokens;
	}

	public Integer getTotalTokens() {
		return totalTokens;
	}

	public void setTotalTokens(Integer totalTokens) {
		this.totalTokens = totalTokens;
	}

	public Integer getLatencyMs() {
		return latencyMs;
	}

	public void setLatencyMs(Integer latencyMs) {
		this.latencyMs = latencyMs;
	}

	public Integer getAttempts() {
		return attempts;
	}

	public void setAttempts(Integer attempts) {
		this.attempts = attempts;
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

	public String getErrorMessage() {
		return errorMessage;
	}

	public void setErrorMessage(String errorMessage) {
		this.errorMessage = errorMessage;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
