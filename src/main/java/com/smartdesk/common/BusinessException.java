package com.smartdesk.common;

/**
 * 业务异常（Day4）。
 *
 * <p>Day4 要点：用自定义异常表达"可预期的业务错误"，
 * 由 {@link GlobalExceptionHandler} 统一转换成 400/404 响应。
 *
 * <p>什么时候抛业务异常 vs 什么时候抛 RuntimeException？
 * <ul>
 *   <li>任务不存在、状态不允许流转、参数非法 → 业务异常（4xx）</li>
 *   <li>数据库连不上、空指针 → 运行时异常（500）</li>
 * </ul>
 * 面试时能说清这个区分，是"写过业务"和"照着教程写"的差别。
 */
public class BusinessException extends RuntimeException {

	private final String code;

	public BusinessException(String code, String message) {
		super(message);
		this.code = code;
	}

	public BusinessException(String code, String message, Throwable cause) {
		super(message, cause);
		this.code = code;
	}

	public String getCode() {
		return code;
	}

}
