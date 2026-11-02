package com.smartdesk.task.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.mapper.TaskMapper;

/**
 * TaskService 单元测试（Day6）。
 *
 * <p>Day6 要点：<b>单元测试不启动 Spring 容器</b>。
 * {@code @ExtendWith(MockitoExtension.class)} + {@code @Mock} + {@code @InjectMocks}
 * 就能在毫秒级完成测试，不需要数据库、不需要端口。
 *
 * <p><b>Mockito 4.x 起严格模式</b>：默认 {@code STRICT_STUBS}，
 * 桩数据没被用到会报错。这能逼你写出真正有意义的测试，
 * 而不是"为了跑通而写的空测试"。
 *
 * <p><b>Spring Boot 4 变化</b>：{@code @MockBean} 已被移除，
 * 改用 {@code @MockitoBean}。本文件不依赖 Spring 上下文，所以不受影响。
 */
@ExtendWith(MockitoExtension.class)
class TaskServiceImplTest {

	@Mock
	private TaskMapper taskMapper;

	@InjectMocks
	private TaskServiceImpl taskService;

	@Test
	@DisplayName("创建任务时状态为空应默认为 TODO")
	void create_shouldDefaultStatusToTodo() {
		Task task = new Task();
		task.setTitle("写周报");
		task.setPriority(3);
		when(taskMapper.insert(any(Task.class))).thenAnswer(inv -> {
			inv.getArgument(0, Task.class).setId(1L);
			return 1;
		});

		Task result = taskService.create(task);

		assertThat(result.getStatus()).isEqualTo(TaskStatus.TODO);
		assertThat(result.getId()).isEqualTo(1L);
		assertThat(result.getCreatedAt()).isNotNull();
		verify(taskMapper).insert(any(Task.class));
	}

	@Test
	@DisplayName("创建任务时应保留已指定的状态")
	void create_shouldKeepProvidedStatus() {
		Task task = new Task();
		task.setTitle("已有状态");
		task.setStatus(TaskStatus.IN_PROGRESS);
		when(taskMapper.insert(any(Task.class))).thenReturn(1);

		Task result = taskService.create(task);

		assertThat(result.getStatus()).isEqualTo(TaskStatus.IN_PROGRESS);
	}

	@Test
	@DisplayName("按 id 查询不存在的任务应返回 null")
	void getById_whenNotFound_shouldReturnNull() {
		when(taskMapper.selectById(999L)).thenReturn(null);

		Task result = taskService.getById(999L);

		assertThat(result).isNull();
	}

	@Test
	@DisplayName("更新不存在的任务状态应返回 null")
	void updateStatus_whenNotFound_shouldReturnNull() {
		when(taskMapper.selectById(999L)).thenReturn(null);

		Task result = taskService.updateStatus(999L, TaskStatus.DONE);

		assertThat(result).isNull();
		verify(taskMapper, never()).updateById(any(Task.class));
	}

	@Test
	@DisplayName("更新存在的任务状态应写入数据库")
	void updateStatus_whenFound_shouldUpdate() {
		Task existing = new Task();
		existing.setId(1L);
		existing.setStatus(TaskStatus.TODO);
		when(taskMapper.selectById(1L)).thenReturn(existing);
		when(taskMapper.updateById(any(Task.class))).thenReturn(1);

		Task result = taskService.updateStatus(1L, TaskStatus.DONE);

		assertThat(result.getStatus()).isEqualTo(TaskStatus.DONE);
		assertThat(result.getUpdatedAt()).isNotNull();
		verify(taskMapper).updateById(existing);
	}

	@Test
	@DisplayName("删除不存在的任务应返回 false")
	void delete_whenNotFound_shouldReturnFalse() {
		when(taskMapper.deleteById(999L)).thenReturn(0);

		boolean deleted = taskService.delete(999L);

		assertThat(deleted).isFalse();
	}

	@Test
	@DisplayName("删除存在的任务应返回 true")
	void delete_whenFound_shouldReturnTrue() {
		when(taskMapper.deleteById(1L)).thenReturn(1);

		boolean deleted = taskService.delete(1L);

		assertThat(deleted).isTrue();
	}

	@Test
	@DisplayName("按状态统计应返回 Mapper 的计数")
	void count_shouldDelegateToMapper() {
		when(taskMapper.selectCount(any())).thenReturn(7L);

		long count = taskService.count(TaskStatus.TODO);

		assertThat(count).isEqualTo(7L);
	}

	@Test
	@DisplayName("列表查询应按优先级和创建时间倒序")
	void list_shouldReturnMapperRecords() {
		Task t1 = new Task();
		t1.setId(1L);
		t1.setStatus(TaskStatus.TODO);
		when(taskMapper.selectPage(any(), any())).thenAnswer(inv -> {
			@SuppressWarnings("unchecked")
			com.baomidou.mybatisplus.extension.plugins.pagination.Page<Task> page =
					inv.getArgument(0);
			page.setRecords(List.of(t1));
			return page;
		});

		List<Task> result = taskService.list(TaskStatus.TODO, 1, 10);

		assertThat(result).hasSize(1);
		assertThat(result.get(0).getId()).isEqualTo(1L);
	}

}
