package com.smartdesk.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.PendingConfirmation;
import com.smartdesk.ai.service.ConfirmationService;
import com.smartdesk.task.dto.TaskQuery;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.service.TaskService;

/**
 * {@link TaskTools} 单元测试（Week4 / Day24-25）。
 *
 * <p><b>这个类守的是"数据边界"和"写操作不落地"两条铁律：</b>
 * <ul>
 *   <li>所有查询的 ownerId 必须等于上下文里的 userId，不能是别的</li>
 *   <li>createTask 只能生成草稿，<b>绝不能调用 taskService.create</b></li>
 * </ul>
 * 第二条用 {@code verify(taskService, never())} 验证 —— 这类"什么都没发生"的断言，
 * 比"返回了正确结果"更能防止回归。
 */
@DisplayName("TaskTools：身份只来自上下文，写操作只生成草稿")
class TaskToolsTest {

	private TaskService taskService;

	private ConfirmationService confirmations;

	private TaskTools tools;

	private final AgentToolContext context = new AgentToolContext(1L, "trace-1", 7L);

	@BeforeEach
	void setUp() {
		taskService = mock(TaskService.class);
		confirmations = mock(ConfirmationService.class);
		tools = new TaskTools(taskService, confirmations, new AiProperties());
	}

	@Test
	@DisplayName("listTasks：ownerId 来自上下文，不来自任何参数")
	void listTasksUsesContextOwner() {
		when(taskService.query(any())).thenReturn(List.of(task(10L, "写周报", TaskStatus.TODO, 2, null)));

		tools.listTasks("TODO", false, 5, context);

		ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
		verify(taskService).query(captor.capture());
		assertThat(captor.getValue().getOwnerId()).isEqualTo(1L);
		assertThat(captor.getValue().getStatus()).isEqualTo(TaskStatus.TODO);
		assertThat(captor.getValue().getPageSize()).isEqualTo(5);
	}

	@Test
	@DisplayName("listTasks：onlyOverdue 只查未完成 + 截止时间早于现在")
	void listTasksOverdue() {
		when(taskService.query(any())).thenReturn(List.of());

		tools.listTasks(null, true, 5, context);

		ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
		verify(taskService).query(captor.capture());
		TaskQuery query = captor.getValue();
		assertThat(query.getDueTo()).isNotNull();
		// 逾期 = 截止时间已过 且 未完成，所以必须排除 DONE / CANCELLED
		assertThat(query.getStatuses()).containsExactlyInAnyOrder(TaskStatus.TODO, TaskStatus.IN_PROGRESS);
	}

	@Test
	@DisplayName("listTasks：limit 被夹在 1..20，模型传 1000 也不会拖库")
	void listTasksClampsLimit() {
		when(taskService.query(any())).thenReturn(List.of());

		tools.listTasks(null, false, 1000, context);
		tools.listTasks(null, false, -5, context);

		ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
		verify(taskService, org.mockito.Mockito.times(2)).query(captor.capture());
		assertThat(captor.getAllValues().get(0).getPageSize()).isEqualTo(20);
		assertThat(captor.getAllValues().get(1).getPageSize()).isEqualTo(1);
	}

	@Test
	@DisplayName("listTasks：中文状态也认（模型经常直接传「待办」）")
	void listTasksAcceptsChineseStatus() {
		when(taskService.query(any())).thenReturn(List.of());

		tools.listTasks("待办", false, 5, context);

		ArgumentCaptor<TaskQuery> captor = ArgumentCaptor.forClass(TaskQuery.class);
		verify(taskService).query(captor.capture());
		assertThat(captor.getValue().getStatus()).isEqualTo(TaskStatus.TODO);
	}

	@Test
	@DisplayName("listTasks：非法状态要报出合法值，模型才能自己改对")
	void listTasksRejectsUnknownStatus() {
		assertThatThrownBy(() -> tools.listTasks("OVERDUE", false, 5, context))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("TODO");
	}

	@Test
	@DisplayName("getTask：别人的任务返回 found=false，不区分「不存在」和「不是你的」")
	void getTaskOtherUserTask() {
		when(taskService.getByIdForOwner(99L, 1L)).thenReturn(null);

		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) tools.getTask(99L, context);

		assertThat(result).containsEntry("found", false);
		// 关键：永远只按 context.userId 查，不可能返回别人的行
		verify(taskService).getByIdForOwner(99L, 1L);
	}

	@Test
	@DisplayName("countTasks：按状态分组 + 总数")
	void countTasks() {
		// any(TaskStatus.class) 不会匹配 null，所以下面 isNull() 的 stub 才会生效
		when(taskService.count(eq(1L), any(TaskStatus.class))).thenReturn(3L);
		when(taskService.count(eq(1L), isNull())).thenReturn(12L);

		@SuppressWarnings("unchecked")
		Map<String, Object> result = (Map<String, Object>) tools.countTasks(context);

		assertThat(result).containsEntry("TODO", 3L).containsEntry("ALL", 12L);
	}

	@Test
	@DisplayName("createTask：只生成草稿，绝不落库")
	void createTaskOnlyCreatesDraft() {
		when(confirmations.create(eq(1L), eq(7L), eq("createTask"), eq("写周报"), isNull(), eq(3), isNull()))
				.thenReturn(new PendingConfirmation("cid-1", 1L, 7L, "createTask", "写周报", null, 3, null,
						System.currentTimeMillis()));

		Object result = tools.createTask("写周报", null, null, null, context);

		assertThat(result).isInstanceOf(ToolConfirmation.class);
		ToolConfirmation confirmation = (ToolConfirmation) result;
		assertThat(confirmation.status()).isEqualTo("PENDING_CONFIRMATION");
		assertThat(confirmation.confirmationId()).isEqualTo("cid-1");
		assertThat(confirmation.message()).contains("确认");

		// 最重要的一条断言：工具没有写库
		verify(taskService, never()).create(any());
	}

	@Test
	@DisplayName("createTask：标题为空直接拒绝，不生成草稿")
	void createTaskRejectsBlankTitle() {
		assertThatThrownBy(() -> tools.createTask("  ", null, null, null, context))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("标题");
		verify(confirmations, never()).create(anyLong(), any(), any(), any(), any(), any(), any());
	}

	@Test
	@DisplayName("createTask：优先级越界被拒绝，不会写进草稿")
	void createTaskRejectsPriority() {
		assertThatThrownBy(() -> tools.createTask("写周报", null, 9, null, context))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("优先级");
	}

	private Task task(Long id, String title, TaskStatus status, Integer priority, LocalDateTime dueDate) {
		Task task = new Task();
		task.setId(id);
		task.setTitle(title);
		task.setStatus(status);
		task.setPriority(priority);
		task.setOwnerId(1L);
		task.setDueDate(dueDate);
		return task;
	}

}
