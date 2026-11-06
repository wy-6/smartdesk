package com.smartdesk.ai.model;

/**
 * AI 调用错误码（Week3 / Day19）。
 *
 * <p><b>核心设计：把"模型服务商的错误"翻译成"我们自己的错误码"。</b>
 *
 * <p>为什么不能直接把上游的 500/429 抛给前端？
 * <ul>
 *   <li>上游错误码语义不统一：OpenAI 429 是限流，某国产模型 429 可能是余额不足</li>
 *   <li>前端不该知道我们用的是哪家模型（信息泄漏 + 换供应商要改前端）</li>
 *   <li><b>是否可重试</b>必须由我们定义：400 参数错重试一万次也没用，
 *       429/超时/5xx 才值得重试</li>
 * </ul>
 *
 * <p><b>面试可讲：重试的三个前提</b> —— 幂等、可恢复、有上限。
 * 模型调用天然幂等（同样的 Prompt 不会改数据库），所以可以重试；
 * 但"写操作"类工具调用绝不能无脑重试，那是第 4 周 Tool Calling 要处理的问题。
 */
public enum AiErrorCode {

	/** 模型响应超时 → 504。 */
	TIMEOUT("AI_TIMEOUT", 504, true, "模型响应超时，请稍后重试"),

	/** 上游限流 → 429。 */
	RATE_LIMITED("AI_RATE_LIMITED", 429, true, "模型服务限流，请稍后重试"),

	/** 上游不可用 → 503。 */
	UPSTREAM_ERROR("AI_UPSTREAM_ERROR", 503, true, "模型服务暂不可用，请稍后重试"),

	/** 模型输出不是合法 JSON / 字段不合法 → 422。 */
	INVALID_OUTPUT("AI_INVALID_OUTPUT", 422, false, "模型输出不合法，无法解析为结构化数据"),

	/** 调用方参数问题 → 400。 */
	INVALID_REQUEST("AI_INVALID_REQUEST", 400, false, "AI 请求参数不合法"),

	/** 服务未配置（例如选了 openai 但没给 Key）→ 503。 */
	NOT_CONFIGURED("AI_NOT_CONFIGURED", 503, false, "模型服务未正确配置"),

	/**
	 * 工具调用轮数超过上限 → 422。
	 *
	 * <p>为什么不是 500？因为这不是"服务器坏了"，而是模型没能收敛。
	 * 用户重说一遍、或者把需求拆小一点就能成功，属于可修正的请求问题。
	 */
	TOOL_LOOP_EXCEEDED("AI_TOOL_LOOP_EXCEEDED", 422, false, "工具调用轮数超过上限，已终止本次处理"),

	/**
	 * 检测到工具重复调用 → 422。
	 *
	 * <p>这是"烧钱"的主要形态：模型卡在同一个工具上反复调用，
	 * 每一轮都是真实计费。必须在第 3 次就掐断，而不是等轮数上限。
	 */
	TOOL_LOOP_DETECTED("AI_TOOL_LOOP_DETECTED", 422, false, "检测到工具重复调用，已终止本次处理");

	private final String code;

	private final int httpStatus;

	private final boolean retryable;

	private final String defaultMessage;

	AiErrorCode(String code, int httpStatus, boolean retryable, String defaultMessage) {
		this.code = code;
		this.httpStatus = httpStatus;
		this.retryable = retryable;
		this.defaultMessage = defaultMessage;
	}

	public String getCode() {
		return code;
	}

	public int getHttpStatus() {
		return httpStatus;
	}

	public boolean isRetryable() {
		return retryable;
	}

	public String getDefaultMessage() {
		return defaultMessage;
	}

}
