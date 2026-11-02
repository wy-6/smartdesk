package com.smartdesk.common;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

/**
 * 全局异常处理（Day4）。
 *
 * <p>Day4 要点：<b>统一异常处理的核心是"不要让异常栈泄漏到前端"</b>。
 * 默认情况下 Spring Boot 会把 {@code MethodArgumentNotValidException}
 * 直接返回给客户端，消息里包含类名、包名、内部字段名，是信息泄漏。
 *
 * <p>{@code @RestControllerAdvice} = {@code @ControllerAdvice} + {@code @ResponseBody}，
 * 意味着它对所有 {@code @RestController} 生效，且返回值直接序列化成 JSON。
 *
 * <p>关键：只有你能预判并归类的异常才写 handler。
 * 兜底的 {@code @ExceptionHandler(Exception.class)} 必须返回通用提示，
 * 同时把完整堆栈打进日志 —— 前端看到"系统繁忙"，日志里有真实原因。
 */
@RestControllerAdvice
public class GlobalExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(GlobalExceptionHandler.class);

	/**
	 * 参数校验失败（{@code @Valid} 触发）。
	 *
	 * <p>把所有字段错误拼成一条消息返回，前端可以���接展示。
	 */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ApiResponse<Void>> handleValidation(MethodArgumentNotValidException ex) {
		String message = ex.getBindingResult().getFieldErrors().stream()
				.map(FieldError::getDefaultMessage)
				.collect(Collectors.joining("; "));
		log.warn("参数校验失败: {}", message);
		return ResponseEntity.badRequest()
				.body(ApiResponse.failure("VALIDATION_ERROR", message));
	}

	/**
	 * 请求体格式错误（比如 JSON 写错、枚举值非法、日期格式不对）。
	 */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ApiResponse<Void>> handleUnreadable(HttpMessageNotReadableException ex) {
		log.warn("请求体解析失败: {}", ex.getMessage());
		return ResponseEntity.badRequest()
				.body(ApiResponse.failure("MALFORMED_REQUEST", "请求体格式错误，请检查 JSON 结构和字段类型"));
	}

	/**
	 * 业务异常 → 400。
	 */
	@ExceptionHandler(BusinessException.class)
	public ResponseEntity<ApiResponse<Void>> handleBusiness(BusinessException ex) {
		log.warn("业务异常 code={} message={}", ex.getCode(), ex.getMessage());
		return ResponseEntity.badRequest()
				.body(ApiResponse.failure(ex.getCode(), ex.getMessage()));
	}

	/**
	 * 资源不存在 → 404。
	 */
	@ExceptionHandler(ResourceNotFoundException.class)
	public ResponseEntity<ApiResponse<Void>> handleNotFound(ResourceNotFoundException ex) {
		log.warn("资源不存在: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiResponse.failure("NOT_FOUND", ex.getMessage()));
	}

	/**
	 * 请求的 URL 没有对应的 Controller 方法 → 404。
	 *
	 * <p>Day4 关键点：<b>兜底的 {@code @ExceptionHandler(Exception.class)} 会把所有异常都吃掉</b>，
	 * 包括框架自己抛的 404。如果不单独处理，用户访问一个不存在的 URL
	 * 会收到 500 —— 这在生产环境会造成误导性告警，也让排查变困难。
	 *
	 * <p>Spring 6+ 用 {@code NoResourceFoundException}（静态资源找不到），
	 * Spring Boot 4 / Spring 7 下这是默认行为；老版本还有
	 * {@code NoHandlerFoundException}（没有匹配的 handler）。
	 * 两个都要处理，覆盖不同配置下的表现。
	 */
	@ExceptionHandler({ NoResourceFoundException.class, NoHandlerFoundException.class })
	public ResponseEntity<ApiResponse<Void>> handleNoHandler(Exception ex) {
		log.warn("请求的接口不存在: {}", ex.getMessage());
		return ResponseEntity.status(HttpStatus.NOT_FOUND)
				.body(ApiResponse.failure("NOT_FOUND", "接口不存在，请检查请求路径"));
	}

	/**
	 * 兜底 → 500。
	 *
	 * <p>注意返回给前端的是通用文案，真实堆栈只进日志。
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ApiResponse<Void>> handleUnexpected(Exception ex) {
		log.error("未预期的异常", ex);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
				.body(ApiResponse.failure("INTERNAL_ERROR", "系统繁忙，请稍后重试"));
	}

}
