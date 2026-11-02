package com.smartdesk.task.controller;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.smartdesk.common.HealthController;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.service.TaskService;

/**
 * TaskController 测试（Day6）。
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
 * <p>这个类同时验证了"校验失败返回统一 JSON 错误结构"这条 Day4 验收标准。
 */
@WebMvcTest(controllers = { TaskController.class, HealthController.class })
class TaskControllerTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private TaskService taskService;

	private Task sampleTask() {
		Task task = new Task();
		task.setId(1L);
		task.setTitle("写周报");
		task.setDescription("整理本周进展");
		task.setStatus(TaskStatus.TODO);
		task.setPriority(3);
		task.setOwnerId(1L);
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

		mockMvc.perform(post("/api/tasks").contentType("application/json").content("""
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
	@DisplayName("POST 标题为空应返回 400 和统一错误结构")
	void create_whenTitleBlank_shouldReturn400() throws Exception {
		mockMvc.perform(post("/api/tasks").contentType("application/json").content("""
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
		mockMvc.perform(post("/api/tasks").contentType("application/json").content("{not-json"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.success").value(false))
				.andExpect(jsonPath("$.errorCode").value("MALFORMED_REQUEST"));
	}

	@Test
	@DisplayName("GET 列表应返回任务数组")
	void list_shouldReturnTasks() throws Exception {
		when(taskService.list(eq(null), anyInt(), anyInt())).thenReturn(List.of(sampleTask()));

		mockMvc.perform(get("/api/tasks"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.success").value(true))
				.andExpect(jsonPath("$.data[0].title").value("写周报"));
	}

	@Test
	@DisplayName("GET 非法 status 应返回 400")
	void list_whenInvalidStatus_shouldReturn400() throws Exception {
		mockMvc.perform(get("/api/tasks").param("status", "NOT_A_STATUS"))
				.andExpect(status().isBadRequest())
				.andExpect(jsonPath("$.errorCode").value("INVALID_STATUS"));
	}

	@Test
	@DisplayName("GET 不存在的任务应返回 404")
	void get_whenNotFound_shouldReturn404() throws Exception {
		when(taskService.getById(999L)).thenReturn(null);

		mockMvc.perform(get("/api/tasks/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

	@Test
	@DisplayName("PUT 更新状态应返回新状态")
	void updateStatus_shouldReturnUpdated() throws Exception {
		Task task = sampleTask();
		task.setStatus(TaskStatus.DONE);
		when(taskService.updateStatus(eq(1L), eq(TaskStatus.DONE))).thenReturn(task);

		mockMvc.perform(put("/api/tasks/1/status").param("status", "DONE"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.data.status").value("DONE"));
	}

	@Test
	@DisplayName("DELETE 不存在的任务应返回 404")
	void delete_whenNotFound_shouldReturn404() throws Exception {
		when(taskService.delete(999L)).thenReturn(false);

		mockMvc.perform(delete("/api/tasks/999"))
				.andExpect(status().isNotFound())
				.andExpect(jsonPath("$.errorCode").value("NOT_FOUND"));
	}

}
