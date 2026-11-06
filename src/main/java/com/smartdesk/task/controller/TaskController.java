package com.smartdesk.task.controller;

import java.time.LocalDateTime;
import java.util.List;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.smartdesk.common.ApiResponse;
import com.smartdesk.common.ResourceNotFoundException;
import com.smartdesk.security.CurrentUser;
import com.smartdesk.task.dto.TaskCreateRequest;
import com.smartdesk.task.dto.TaskQuery;
import com.smartdesk.task.dto.TaskResponse;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.service.TaskService;

import jakarta.validation.Valid;

/**
 * 任务 REST 接口（Day3 建立，Week2 扩展权限隔离与条件查询）。
 *
 * <p>Day3 要点：<b>三种参数来源的区别</b>
 * <ul>
 *   <li>{@code @PathVariable} —— 路径参数，{@code /api/tasks/{id}}，定位资源用</li>
 *   <li>{@code @RequestParam} —— 查询参数，{@code ?status=TODO&page=1}，过滤/分页用</li>
 *   <li>{@code @RequestBody} —— 请求体，JSON 解析成对象，传输结构化数据用</li>
 * </ul>
 *
 * <p>还有第四种：{@code @RequestHeader}，取请求头（如 Authorization）。
 *
 * <p><b>REST 约定</b>：GET 查询不产生副作用，POST 创建返回 201，
 * PUT/PATCH 更新，DELETE 删除返回 204。路径用复数名词（{@code /tasks}），
 * 而不是动词（{@code /getTask}）。
 *
 * <p><b>Day11 核心改动：所有接口都必须取当前登录用户。</b>
 *
 * <p>看这个类的每个方法，第一件事都是 {@code CurrentUser.getUserId()}。
 * <b>ownerId 永远来自登录态，绝不来自请求参数</b> —— 这是防越权的铁律。
 *
 * <p>如果接口接受客户端传的 ownerId，比如：
 * <pre>{@code GET /api/tasks?ownerId=2}</pre>
 * 那么用户改成 2 就能看到别人的数据。任何"从请求里取归属信息"的写法都是漏洞。
 */
@RestController
@RequestMapping("/api/tasks")
public class TaskController {

	private final TaskService taskService;

	/**
	 * 构造器注入 Controller 依赖。
	 *
	 * <p>Day4 要点：Controller 保持"薄"。它只做三件事：
	 * 参数绑定与校验 → 调用 Service → 包装响应。
	 * 任何 if-else 业务判断出现在这里，都是信号：逻辑该下沉到 Service。
	 */
	public TaskController(TaskService taskService) {
		this.taskService = taskService;
	}

	/**
	 * 创建任务。
	 *
	 * <p>{@code @Valid} 触发 DTO 上的校验注解；
	 * 校验失败抛 {@code MethodArgumentNotValidException}，
	 * 被 {@code GlobalExceptionHandler} 统一转成 400。
	 */
	@PostMapping
	public ResponseEntity<ApiResponse<TaskResponse>> create(@Valid @RequestBody TaskCreateRequest request) {
		Long currentUserId = requireCurrentUserId();

		Task task = new Task();
		task.setTitle(request.getTitle());
		task.setDescription(request.getDescription());
		task.setPriority(request.getPriority());
		task.setDueDate(request.getDueDate());
		task.setStatus(TaskStatus.TODO);
		// 归属人 = 当前登录用户，客户端无法指定
		task.setOwnerId(currentUserId);

		Task created = taskService.create(task);
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(TaskResponse.from(created)));
	}

	/**
	 * 查询任务列表（支持多条件筛选，Day8）。
	 *
	 * <p>支持的筛选条件：
	 * <pre>
	 * GET /api/tasks?status=TODO&amp;minPriority=3&amp;dueFrom=2026-11-01T00:00:00&amp;page=1&amp;pageSize=20
	 * </pre>
	 */
	@GetMapping
	public ResponseEntity<ApiResponse<List<TaskResponse>>> list(
			@RequestParam(required = false) String status,
			@RequestParam(required = false) Integer minPriority,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dueFrom,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE_TIME) LocalDateTime dueTo,
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "10") int pageSize) {

		Long currentUserId = requireCurrentUserId();

		// 非法枚举值转成 400，而不是让 Service 收到 null 静默返回全量数据
		TaskStatus taskStatus = null;
		if (status != null && !status.isBlank()) {
			taskStatus = TaskStatus.fromCode(status);
			if (taskStatus == null) {
				return ResponseEntity.badRequest()
						.body(ApiResponse.failure("INVALID_STATUS",
								"非法的任务状态: " + status + "，可选值: TODO/IN_PROGRESS/DONE/CANCELLED"));
			}
		}

		TaskQuery query = new TaskQuery();
		query.setOwnerId(currentUserId);
		query.setStatus(taskStatus);
		query.setMinPriority(minPriority);
		query.setDueFrom(dueFrom);
		query.setDueTo(dueTo);
		query.setPageNum(page);
		query.setPageSize(pageSize);

		List<TaskResponse> tasks = taskService.query(query).stream()
				.map(TaskResponse::from)
				.toList();
		return ResponseEntity.ok(ApiResponse.success(tasks));
	}

	/**
	 * 查询单个任务。
	 *
	 * <p><b>用 {@code getByIdForOwner} 而不是 {@code getById}</b>：
	 * 前者带 ownerId 过滤，别人家的任务查出来就是 null → 404。
	 */
	@GetMapping("/{id}")
	public ResponseEntity<ApiResponse<TaskResponse>> get(@PathVariable Long id) {
		Long currentUserId = requireCurrentUserId();
		Task task = taskService.getByIdForOwner(id, currentUserId);
		if (task == null) {
			throw ResourceNotFoundException.task(id);
		}
		return ResponseEntity.ok(ApiResponse.success(TaskResponse.from(task)));
	}

	/**
	 * 更新任务状态。
	 */
	@PutMapping("/{id}/status")
	public ResponseEntity<ApiResponse<TaskResponse>> updateStatus(
			@PathVariable Long id,
			@RequestParam String status) {

		Long currentUserId = requireCurrentUserId();

		TaskStatus taskStatus = TaskStatus.fromCode(status);
		if (taskStatus == null) {
			return ResponseEntity.badRequest()
					.body(ApiResponse.failure("INVALID_STATUS", "非法的任务状态: " + status));
		}
		Task updated = taskService.updateStatus(id, currentUserId, taskStatus);
		if (updated == null) {
			throw ResourceNotFoundException.task(id);
		}
		return ResponseEntity.ok(ApiResponse.success(TaskResponse.from(updated)));
	}

	/**
	 * 删除任务。
	 */
	@DeleteMapping("/{id}")
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
		Long currentUserId = requireCurrentUserId();
		if (!taskService.delete(id, currentUserId)) {
			throw ResourceNotFoundException.task(id);
		}
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	/**
	 * 取当前登录用户 id。
	 *
	 * <p>理论上 SecurityConfig 已经保证所有 {@code /api/tasks/**} 都需要认证，
	 * 走到这里一定有用户。但仍然做一次判空 —— <b>防御性编程</b>，
	 * 万一以后有人改了安全配置，这里返回 401 而不是 NPE 500。
	 */
	private Long requireCurrentUserId() {
		Long userId = CurrentUser.getUserId();
		if (userId == null) {
			throw new com.smartdesk.common.BusinessException("UNAUTHORIZED", "未登录");
		}
		return userId;
	}

}
