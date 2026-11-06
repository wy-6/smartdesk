package com.smartdesk.common;

import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.HandlerMethodValidationException;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import org.springframework.web.servlet.NoHandlerFoundException;
import org.springframework.web.servlet.resource.NoResourceFoundException;

import com.smartdesk.ai.model.AiException;

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
	 * 单个参数绑定失败（Day9 新增）。
	 *
	 * <p><b>典型场景</b>：路径变量或查询参数的类型转换失败。
	 * <pre>
	 * GET /api/tasks/abc   →  "abc" 转 Long 失败
	 * GET /api/tasks?page=x → "x" 转 int 失败
	 * </pre>
	 *
	 * <p>这类异常如果不处理，前端拿到的是 500（因为兜底 handler 抓走了），
	 * 但它本质上是<b>客户端的错</b>，应该返回 400。
	 *
	 * <p>面试可讲：{@code MethodArgumentTypeMismatchException} vs
	 * {@code MissingServletRequestParameterException} vs
	 * {@code HttpMessageNotReadableException} 的区别：
	 * 前两个是"URL 上的参数"出错，后一个是"请求体"出错。
	 */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ApiResponse<Void>> handleTypeMismatch(MethodArgumentTypeMismatchException ex) {
		log.warn("参数类型不匹配: name={} value={}", ex.getName(), ex.getValue());
		return ResponseEntity.badRequest()
				.body(ApiResponse.failure("INVALID_PARAMETER",
						"参数 '" + ex.getName() + "' 的值 '" + ex.getValue() + "' 格式不正确"));
	}

	/**
	 * 缺少必填参数（Day9 新增）。
	 *
	 * <p>例如接口声明了 {@code @RequestParam String status}（required 默认 true），
	 * 但调用时没传这个参数。
	 */
	@ExceptionHandler(MissingServletRequestParameterException.class)
	public ResponseEntity<ApiResponse<Void>> handleMissingParam(MissingServletRequestParameterException ex) {
		log.warn("缺少必填参数: {}", ex.getParameterName());
		return ResponseEntity.badRequest()
				.body(ApiResponse.failure("MISSING_PARAMETER", "缺少必填参数: " + ex.getParameterName()));
	}

	/**
	 * 方法级参数校验失败（Day9 新增）。
	 *
	 * <p>和 {@code MethodArgumentNotValidException} 的区别：
	 * <ul>
	 *   <li>{@code MethodArgumentNotValidException}：DTO 对象上的 {@code @Valid} 校验失败</li>
	 *   <li>{@code HandlerMethodValidationException}：直接在方法参数上写的约束失败，
	 *       比如 {@code @RequestParam @Min(1) int page}</li>
	 * </ul>
	 * Spring Boot 3.2+ / Spring 6.1+ 引入了方法级校验，
	 * 报的是 {@code HandlerMethodValidationException} 而不是老版本的
	 * {@code ConstraintViolationException}。
	 */
	@ExceptionHandler(HandlerMethodValidationException.class)
	public ResponseEntity<ApiResponse<Void>> handleMethodValidation(HandlerMethodValidationException ex) {
		log.warn("方法参数校验失败: {}", ex.getMessage());
		return ResponseEntity.badRequest()
				.body(ApiResponse.failure("VALIDATION_ERROR", "参数校验失败，请检查参数取值范围"));
	}

	/**
	 * AI 调用异常（Week3 / Day19）→ 按错误码映射状态码。
	 *
	 * <p><b>为什么不能统一映射成 500？</b>
	 * 因为前端要对不同情况做不同处理：
	 * <ul>
	 *   <li>{@code AI_TIMEOUT} (504)：可以提示"重试"</li>
	 *   <li>{@code AI_RATE_LIMITED} (429)：可以退避后自动重试</li>
	 *   <li>{@code AI_INVALID_OUTPUT} (422)：重试没用，要让用户改写输入</li>
	 * </ul>
	 * 全都返回 500，前端就只能"弹个红色提示"，体验和可维护性都差。
	 */
	@ExceptionHandler(AiException.class)
	public ResponseEntity<ApiResponse<Void>> handleAi(AiException ex) {
		log.warn("AI 调用失败 code={} attempts={} message={}", ex.getCode().getCode(), ex.getAttempts(),
				ex.getMessage());
		return ResponseEntity.status(ex.getCode().getHttpStatus())
				.body(ApiResponse.failure(ex.getCode().getCode(), ex.getMessage()));
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
