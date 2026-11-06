package com.smartdesk.ai.model;

/**
 * AI 调用异常（Week3 / Day19）。
 *
 * <p>继承 {@link RuntimeException}：模型调用失败属于"基础设施故障"，
 * 不是业务规则冲突，所以不用 {@code BusinessException}（那会被映射成 400）。
 *
 * <p>{@code attempts} 记录"这次失败一共打了几次上游"，
 * 会随错误一起返回给前端并写进调用日志 —— 排查"到底重试了没有"时非常有用。
 */
public class AiException extends RuntimeException {

	private final AiErrorCode code;

	private int attempts = 1;

	public AiException(AiErrorCode code) {
		this(code, code.getDefaultMessage(), null);
	}

	public AiException(AiErrorCode code, String message) {
		this(code, message, null);
	}

	public AiException(AiErrorCode code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public AiErrorCode getCode() {
		return code;
	}

	public int getAttempts() {
		return attempts;
	}

	/** 由重试器在抛出前回填，返回自身方便链式调用。 */
	public AiException withAttempts(int attempts) {
		this.attempts = attempts;
		return this;
	}

}
