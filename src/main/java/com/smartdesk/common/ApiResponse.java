package com.smartdesk.common;

import java.time.LocalDateTime;

/**
 * 统一响应结构（Day4）。
 *
 * <p>Day4 要点：统一响应体让前端只需要处理一种格式。
 * 成功和失败都用同一个外壳，失败时 {@code success=false} 且 {@code error} 有值。
 *
 * @param <T> 业务数据类型
 */
public class ApiResponse<T> {

	/** 是否成功。 */
	private boolean success;

	/** 业务数据。失败时为 null。 */
	private T data;

	/** 错误码。成功时为 null。 */
	private String errorCode;

	/** 错误信息。成功时为 null。 */
	private String errorMessage;

	/** 服务器时间戳，前端可用于排查时序问题。 */
	private LocalDateTime timestamp;

	public ApiResponse() {
		this.timestamp = LocalDateTime.now();
	}

	public static <T> ApiResponse<T> success(T data) {
		ApiResponse<T> response = new ApiResponse<>();
		response.setSuccess(true);
		response.setData(data);
		return response;
	}

	public static <T> ApiResponse<T> failure(String errorCode, String errorMessage) {
		ApiResponse<T> response = new ApiResponse<>();
		response.setSuccess(false);
		response.setErrorCode(errorCode);
		response.setErrorMessage(errorMessage);
		return response;
	}

	public boolean isSuccess() {
		return success;
	}

	public void setSuccess(boolean success) {
		this.success = success;
	}

	public T getData() {
		return data;
	}

	public void setData(T data) {
		this.data = data;
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

	public LocalDateTime getTimestamp() {
		return timestamp;
	}

	public void setTimestamp(LocalDateTime timestamp) {
		this.timestamp = timestamp;
	}

}
