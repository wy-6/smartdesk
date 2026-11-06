package com.smartdesk.task.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.WebMvcTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.smartdesk.common.HealthController;
import com.smartdesk.config.SecurityConfig;
import com.smartdesk.security.JwtPrincipal;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.service.TaskService;

/**
 * TaskController 测试（Day6 建立，Week2 扩展权限隔离）。
 *
 * <p>Day6 要点：<b>Web 层测试用 {@code @WebMvcTest}</b>，
 * 它只加载 Web 层组件，不启动数据库、不加载 Service 实现。
 * 配合 {@code @MockitoBean} 注入 Service 的 Mock。
 *
 * <p><b>注意 {@code @WebMvcTest} 的切片范围</b>：它只扫描 {@code controllers} 里
 * 显式列出的 Controller（以及 {@code @ControllerAdvice}）。
 * 想测多个 Controller 就得都列上 —— {@code @WebMvcTest(TaskController.class)}
 * 不会自动把 {@code HealthController} 带进来。
 *
 * <p><b>Spring Boot 4 的两个破坏性变化</b>（旧教程会踩）：
 * <ol>
 *   <li>{@code @MockBean} 已删除 → 改用 {@code @MockitoBean}</li>
 *   <li>{@code @SpringBootTest} 不再自动提供 MockMvc → 需加 {@code @AutoConfigureMockMvc}</li>
 * </ol>
 *
 * <p><b>Week2 关键：{@code @Import(SecurityConfig.class)} 不能少。</b>
 *
 * <p>{@code @WebMvcTest} 的组件扫描范围很窄，普通的 {@code @Configuration}
 * 类（比如我们的 {@code SecurityConfig}）默认<b>不会</b>被扫到。
 * 不 import 的话，Spring Security 的默认配置会生效（表单登录 + httpBasic），
 * 我们精心设计的 {@code authenticationEntryPoint} 统一 JSON 响应就测不到。
 *
 * <p>另外切片里还缺 {@code JwtService}（它是普通 {@code @Component}），
 * 必须用 {@code @MockitoBean} 补上，否则报
 * "No qualifying bean of type 'JwtService' available"。
 */
@WebMvcTest(controllers = { TaskController.class, HealthController.class })
@Import(SecurityConfig.class)
class TaskControllerTest {

	/** 模拟登录的用户 id。 */
	private static final Long USER_ID = 1L;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TaskService taskService;

	/**
	 * Week2：{@code @WebMvcTest} 切片会加载 {@code SecurityConfig} 和
	 * {@code JwtAuthenticationFilter}（它们是 Web 层组件），
	 * 但 {@code JwtService} 是普通 {@code @Component}，不在切片范围内。
	 *
	 * <p>所以必须用 {@code @MockitoBean} 提供一个假实现，
	 * 否则会报 "No qualifying bean of type 'JwtService' available"。
	 *
	 * <p><b>面试可讲：</b>这是切片的固有特性 —— 它只加载特定类型的 Bean。
	 * 遇到"缺 Bean"的报错时，先想"这个 Bean 属于哪个切片"，再决定 mock 它
	 * 还是把它加进切片。
	 */
	@MockitoBean
	private com.smartdesk.security.JwtService jwtService;

	/**
	 * 构造一个"已登录"的请求后置处理器。
	 *
	 * <p>{@code authentication(...)} 会在请求进入 Controller 前，
	 * 把指定的 Authentication 放进 SecurityContextHolder，
	 * 这样 {@code CurrentUser.getUserId()} 就能拿到值。
	 */
	private RequestPostProcessor loginAs(Long userId) {
		JwtPrincipal principal = new JwtPrincipal(userId, "tester");
		UsernamePasswordAuthenticationToken auth = new UsernamePasswordAuthenticationToken(
				principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
		return authentication(auth);
	}

	private Task sampleTask() {
		Task task = new Task();
		task.setId(1L);
		task.setTitle("写周报");
		task.setDescription("整理本周进展");
		task.setStatus(TaskStatus.TODO);
		task.setPriority(3);
		task.setOwnerId(USER_ID);
		task.setDueDate(LocalDateTime.of(2026, 12, 31, 18, 0));
		task.setCreatedAt(LocalDateTime.now());
		task.setUpdatedAt(LocalDateTime.now());
		return task;
	}

	@Test
	@DisplayName("GET /api/health 应返回 UP")
	void health_shouldReturnUp() throws Exception {
		mockMvc.perform(get("/api/health"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.status").value("UP"));
	}

	@Test
	@DisplayName("POST 创建任务成功应返回 201")
	void create_whenValid_shouldReturn201() throws Exception {
		when(taskService.create(any(Task.class))).thenAnswer(inv -> {
			Task t = inv.getArgument(0);
			t.setId(1L);
			return t;
		});

		mockMvc.perform(post("/api/tasks").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{
								  "title": "写周报",
								  "description": "整理本周进展",
								  "priority": 3,
								  "dueDate": "2026-12-31T18:00:00"
								}
								"""))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data.id").value(1))
				.andExpect(jsonPath("$.data.status").value("TODO"));
	}

	@Test
	@DisplayName("POST 创建任务时 ownerId 必须取自登录态，不能由客户端指定")
	void create_shouldTakeOwnerIdFromAuthentication() throws Exception {
		when(taskService.create(any(Task.class))).thenAnswer(inv -> {
			Task t = inv.getArgument(0);
			t.setId(1L);
			return t;
		});

		mockMvc.perform(post("/api/tasks").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{"title":"写周报","priority":3,"dueDate":"2026-12-31T18:00:00"}
								"""))
				.andExpect(status().isCreated());

		// 验证传给 Service 的 Task 里，ownerId 就是登录用户的 id
		org.mockito.ArgumentCaptor<Task> captor = org.mockito.ArgumentCaptor.forClass(Task.class);
		verify(taskService).create(captor.capture());
		org.assertj.core.api.Assertions.assertThat(captor.getValue().getOwnerId()).isEqualTo(USER_ID);
	}

	@Test
	@DisplayName("POST 标题为空应返回 400 和统一错误结构")
	void create_whenTitleBlank_shouldReturn400() throws Exception {
		mockMvc.perform(post("/api/tasks").with(loginAs(USER_ID))
						.contentType("application/json").content("""
								{
								  "title": "",
								  "priority": 9,
								  "dueDate": "2026-12-31T18:00:00"
								}
								"""))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.errorCode").value("VALIDATION_ERROR"))
				.andExpect(jsonPath("$.errorMessage").exists());
	}

	@Test
	@DisplayName("POST JSON 格式错误应返回 400")
	void create_whenMalformedJson_shouldReturn400() throws Exception {
		mockMvc.perform(post("/api/tasks").with(loginAs(USER_ID))
						.contentType("application/json").content("{not-json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
	}

	@Test
	@DisplayName("GET 列表应返回任务数组")
	void list_shouldReturnTasks() throws Exception {
		when(taskService.query(any())).thenReturn(List.of(sampleTask()));

		mockMvc.perform(get("/api/tasks").with(loginAs(USER_ID)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].title").value("写周报"));
	}

	@Test
	@DisplayName("GET 列表必须带上当前用户的 ownerId")
	void list_shouldFilterByCurrentUser() throws Exception {
		when(taskService.query(any())).thenReturn(List.of());

		mockMvc.perform(get("/api/tasks").with(loginAs(USER_ID)))
				.andExpect(status().isOk());

		org.mockito.ArgumentCaptor<com.smartdesk.task.dto.TaskQuery> captor =
				org.mockito.ArgumentCaptor.forClass(com.smartdesk.task.dto.TaskQuery.class);
		verify(taskService).query(captor.capture());
		org.assertj.core.api.Assertions.assertThat(captor.getValue().getOwnerId()).isEqualTo(USER_ID);
	}

	@Test
	@DisplayName("GET 非法 status 应返回 400")
	void list_whenInvalidStatus_shouldReturn400() throws Exception {
		mockMvc.perform(get("/api/tasks").with(loginAs(USER_ID)).param("status", "NOT_A_STATUS"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_STATUS"));
	}

	@Test
	@DisplayName("GET 不存在的任务应返回 404")
	void get_whenNotFound_shouldReturn404() throws Exception {
		when(taskService.getByIdForOwner(eq(999L), eq(USER_ID))).thenReturn(null);

		mockMvc.perform(get("/api/tasks/999").with(loginAs(USER_ID)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("GET 访问他人任务应返回 404（不泄露资源存在性）")
	void get_whenNotOwner_shouldReturn404() throws Exception {
		// Service 层用 id + owner_id 过滤，别人的任务查出来就是 null
		when(taskService.getByIdForOwner(eq(1L), eq(USER_ID))).thenReturn(null);

		mockMvc.perform(get("/api/tasks/1").with(loginAs(USER_ID)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("PUT 更新状态应返回新状态")
	void updateStatus_shouldReturnUpdated() throws Exception {
		Task task = sampleTask();
		task.setStatus(TaskStatus.DONE);
		when(taskService.updateStatus(eq(1L), eq(USER_ID), eq(TaskStatus.DONE))).thenReturn(task);

		mockMvc.perform(put("/api/tasks/1/status").with(loginAs(USER_ID)).param("status", "DONE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("DONE"));
	}

	@Test
	@DisplayName("PUT 更新他人的任务应返回 404")
	void updateStatus_whenNotOwner_shouldReturn404() throws Exception {
		when(taskService.updateStatus(eq(1L), eq(USER_ID), eq(TaskStatus.DONE))).thenReturn(null);

		mockMvc.perform(put("/api/tasks/1/status").with(loginAs(USER_ID)).param("status", "DONE"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("DELETE 不存在的任务应返回 404")
	void delete_whenNotFound_shouldReturn404() throws Exception {
		when(taskService.delete(eq(999L), eq(USER_ID))).thenReturn(false);

		mockMvc.perform(delete("/api/tasks/999").with(loginAs(USER_ID)))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("DELETE 删除他人的任务应返回 404")
	void delete_whenNotOwner_shouldReturn404() throws Exception {
		when(taskService.delete(eq(1L), eq(USER_ID))).thenReturn(false);

		mockMvc.perform(delete("/api/tasks/1").with(loginAs(USER_ID)))
				.andExpect(status().isNotFound());
	}

}
