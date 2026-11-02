package com.smartdesk.common;

/**
 * 资源不存在异常（Day4）。
 *
 * <p>与 {@link BusinessException} 分开，是为了能返回 404 而不是 400。
 * "任务不存在"和"参数非法"在 HTTP 语义上是两回事，面试常问。
 */
public class ResourceNotFoundException extends RuntimeException {

	public ResourceNotFoundException(String message) {
		super(message);
	}

	public static ResourceNotFoundException task(Long id) {
		return new ResourceNotFoundException("任务不存在: id=" + id);
	}

}
