package com.smartdesk.ai.tool;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.PendingConfirmation;
import com.smartdesk.ai.service.ConfirmationService;
import com.smartdesk.task.dto.TaskQuery;
import com.smartdesk.task.entity.Task;
import com.smartdesk.task.entity.TaskStatus;
import com.smartdesk.task.service.TaskService;

/**
 * 任务域工具集（Week4 / Day23-25）。
 *
 * <p><b>四个工具，覆盖"读 / 查 / 统计 / 写"四类典型场景：</b>
 * <table border="1">
 * <tr><th>工具</th><th>副作用</th><th>为什么需要它</th></tr>
 * <tr><td>listTasks</td><td>无</td><td>最高频。"我有什么任务""哪些逾期了"</td></tr>
 * <tr><td>getTask</td><td>无</td><td>列表只给摘要，追问细节时要能取单条</td></tr>
 * <tr><td>countTasks</td><td>无</td><td>"我还有多少没做"—— 不该让模型去数列表</td></tr>
 * <tr><td>createTask</td><td><b>有</b></td><td>唯一会改数据的工具，必须走确认</td></tr>
 * </table>
 *
 * <p><b>面试重点：工具描述（description）怎么写才有效？</b>
 * 三条规则，每条都是踩过坑总结的：
 * <ol>
 *   <li><b>写"什么时候用"，不只写"做什么"</b>：模型是拿用户的话去匹配描述的，
 *       "查询任务列表"匹配不上"我有什么事要过期了"</li>
 *   <li><b>把否定条件写进去</b>："只能查当前用户，不能查别人" —— 虽然真正的边界在代码里，
 *       但在描述里说清楚能减少模型的无意义尝试</li>
 *   <li><b>写清边界值</b>：limit 的范围、priority 的取值范围。模型很容易传 limit=1000</li>
 * </ol>
 *
 * <p><b>注意所有方法的 {@link AgentToolContext} 参数</b>：它不会出现在 JSON Schema 里，
 * 模型看不见、也传不进来。用户身份只能从登录态取 —— 这是 Day24 的核心。
 */
@Component
public class TaskTools implements ToolProvider {

	private static final DateTimeFormatter DISPLAY = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm");

	private static final int MAX_LIST_LIMIT = 20;

	private static final int DEFAULT_LIST_LIMIT = 5;

	private final TaskService taskService;

	private final ConfirmationService confirmations;

	private final AiProperties properties;

	public TaskTools(TaskService taskService, ConfirmationService confirmations, AiProperties properties) {
		this.taskService = taskService;
		this.confirmations = confirmations;
		this.properties = properties;
	}

	// ------------------------------------------------------------------
	// 只读工具
	// ------------------------------------------------------------------

	@Tool(name = "listTasks", description = """
			查询当前登录用户自己的任务列表。当用户问"我有哪些任务""有什么快到期了"
			"帮我看看逾期的事""这周要做什么"时使用。
			只返回当前用户的数据，无法查询其他人的任务。
			""")
	public Object listTasks(
			@ToolParam(required = false, description = "状态过滤：TODO 待办 / IN_PROGRESS 进行中 / DONE 已完成 / CANCELLED 已取消。不传表示全部状态") String status,
			@ToolParam(required = false, description = "为 true 时只返回已逾期（截止时间已过且未完成）的任务") Boolean onlyOverdue,
			@ToolParam(required = false, description = "返回条数上限，1 到 20，默认 5") Integer limit,
			AgentToolContext context) {

		int size = limit == null ? DEFAULT_LIST_LIMIT : Math.max(1, Math.min(MAX_LIST_LIMIT, limit));
		TaskStatus filter = parseStatus(status);

		TaskQuery query = new TaskQuery();
		// 数据边界：只认登录态里的 userId
		query.setOwnerId(context.userId());
		query.setPageNum(1);
		query.setPageSize(size);

		boolean overdueOnly = Boolean.TRUE.equals(onlyOverdue);
		if (filter != null) {
			query.setStatus(filter);
		}
		if (overdueOnly) {
			query.setDueTo(LocalDateTime.now());
			if (filter == null) {
				// "逾期"的定义：截止时间已过 且 还没完成。已完成/已取消的不算逾期
				query.setStatuses(List.of(TaskStatus.TODO, TaskStatus.IN_PROGRESS));
			}
		}

		List<Task> tasks = taskService.query(query);
		List<TaskItem> items = new ArrayList<>(tasks.size());
		for (Task task : tasks) {
			items.add(toItem(task));
		}

		Map<String, Object> result = new LinkedHashMap<>();
		result.put("count", items.size());
		result.put("onlyOverdue", overdueOnly);
		result.put("tasks", items);
		if (items.isEmpty()) {
			// 明确告诉模型"确实没有"，避免它编一条出来
			result.put("message", overdueOnly ? "当前没有逾期任务" : "没有符合条件的任务");
		}
		return result;
	}

	@Tool(name = "getTask", description = """
			按任务 id 查询单个任务的完整信息。当用户追问某个任务的细节，
			或者需要确认某个任务的状态、截止时间、优先级时使用。
			只能查询当前用户自己的任务；任务不存在或不属于当前用户时返回 found=false。
			""")
	public Object getTask(@ToolParam(description = "任务 id，必须是数字") Long taskId, AgentToolContext context) {
		if (taskId == null) {
			throw new IllegalArgumentException("taskId 不能为空");
		}
		Task task = taskService.getByIdForOwner(taskId, context.userId());
		Map<String, Object> result = new LinkedHashMap<>();
		if (task == null) {
			// 不区分"不存在"与"是别人的"：和第 2 周 HTTP 层返回 404 而非 403 完全一致
			result.put("found", false);
			result.put("message", "任务不存在或不属于当前用户");
			return result;
		}
		result.put("found", true);
		result.put("task", toItem(task));
		return result;
	}

	@Tool(name = "countTasks", description = """
			统计当前用户各状态的任务数量。当用户问"我还有多少没做""完成了几件"
			"一共有多少任务"时使用。数量类问题应该用它，不要用 listTasks 自己数。
			""")
	public Object countTasks(AgentToolContext context) {
		Map<String, Object> counts = new LinkedHashMap<>();
		for (TaskStatus status : TaskStatus.values()) {
			counts.put(status.name(), taskService.count(context.userId(), status));
		}
		counts.put("ALL", taskService.count(context.userId(), null));
		return counts;
	}

	// ------------------------------------------------------------------
	// 有副作用的工具
	// ------------------------------------------------------------------

	/**
	 * 创建任务 —— <b>只生成草稿，不落库</b>。
	 *
	 * <p>注意 description 里那句"本工具不会直接创建"。这是写给模型看的，
	 * 目的是压住它"邀功"的冲动：如果不说，模型很容易在 tool result 还没回来时
	 * 就对用户说"已为你创建任务 X"。用户去列表里一看没有，信任直接崩。
	 */
	@Tool(name = "createTask", description = """
			为当前用户创建一个新任务。只有用户明确要求"帮我建一个任务""记一下这件事"时才调用。
			注意：本工具不会直接创建任务，而是返回一份待确认草稿（status=PENDING_CONFIRMATION），
			必须由用户在界面上点确认后才真正创建。
			因此不要对用户说"已经创建好了"，要告诉用户"已生成草稿，请确认"。
			""")
	@SideEffect("创建任务")
	public Object createTask(
			@ToolParam(description = "任务标题，必填，不超过 200 字，要具体可执行") String title,
			@ToolParam(required = false, description = "任务描述，不超过 2000 字") String description,
			@ToolParam(required = false, description = "优先级，1 到 5 的整数，5 最高，默认 3") Integer priority,
			@ToolParam(required = false, description = "截止时间，ISO-8601 格式如 2026-11-07T18:00:00。用户没说截止时间就不要传，不要编造") LocalDateTime dueDate,
			AgentToolContext context) {

		if (title == null || title.isBlank()) {
			throw new IllegalArgumentException("任务标题不能为空");
		}
		String normalizedTitle = title.strip();
		if (normalizedTitle.length() > 200) {
			throw new IllegalArgumentException("任务标题不能超过 200 字，当前 " + normalizedTitle.length() + " 字");
		}
		if (description != null && description.length() > 2000) {
			throw new IllegalArgumentException("任务描述不能超过 2000 字，当前 " + description.length() + " 字");
		}
		int normalizedPriority = priority == null ? 3 : priority;
		if (normalizedPriority < 1 || normalizedPriority > 5) {
			throw new IllegalArgumentException("优先级必须是 1 到 5 之间的整数，收到：" + normalizedPriority);
		}

		String dueDateIso = dueDate == null ? null : dueDate.toString();
		PendingConfirmation pending = confirmations.create(context.userId(), context.sessionId(), "createTask",
				normalizedTitle, description, normalizedPriority, dueDateIso);

		Map<String, Object> preview = new LinkedHashMap<>();
		preview.put("title", pending.title());
		preview.put("description", pending.description());
		preview.put("priority", pending.priority());
		preview.put("dueDate", pending.dueDate() == null ? null : formatDue(pending.dueDate()));

		long ttlSeconds = properties.getAgent().getConfirmationTtl().toSeconds();
		return new ToolConfirmation(ToolConfirmation.PENDING, pending.confirmationId(), ttlSeconds, preview,
				"已生成待确认草稿，需要用户在界面上确认后才会真正创建。请如实告诉用户「已生成草稿，请确认」，不要说已经创建完成。");
	}

	// ------------------------------------------------------------------
	// 辅助
	// ------------------------------------------------------------------

	private TaskStatus parseStatus(String status) {
		if (status == null || status.isBlank()) {
			return null;
		}
		String text = status.strip().toUpperCase();
		// 容忍模型把中文状态直接传进来（"待办"），这是真实调用里很常见的偏差
		return switch (text) {
			case "TODO", "待办", "待处理", "未开始" -> TaskStatus.TODO;
			case "IN_PROGRESS", "进行中", "处理中" -> TaskStatus.IN_PROGRESS;
			case "DONE", "已完成", "完成" -> TaskStatus.DONE;
			case "CANCELLED", "已取消", "取消" -> TaskStatus.CANCELLED;
			default -> throw new IllegalArgumentException(
					"status 必须是 TODO / IN_PROGRESS / DONE / CANCELLED 之一，收到：" + status);
		};
	}

	private TaskItem toItem(Task task) {
		return new TaskItem(task.getId(), task.getTitle(), task.getStatus() == null ? null : task.getStatus().name(),
				task.getPriority(), task.getDueDate() == null ? null : formatDue(task.getDueDate().toString()),
				isOverdue(task));
	}

	private boolean isOverdue(Task task) {
		if (task.getDueDate() == null || task.getStatus() == null) {
			return false;
		}
		boolean unfinished = task.getStatus() == TaskStatus.TODO || task.getStatus() == TaskStatus.IN_PROGRESS;
		return unfinished && task.getDueDate().isBefore(LocalDateTime.now());
	}

	/** 把 ISO 时间转成"人能读"的形式，同时保留原始信息量（不含秒）。 */
	private String formatDue(String iso) {
		try {
			return LocalDateTime.parse(iso).format(DISPLAY);
		}
		catch (RuntimeException ex) {
			return iso;
		}
	}

	/**
	 * 给模型看的任务摘要。
	 *
	 * <p>为什么只放这几个字段？模型不需要 {@code ownerId}（它不该知道内部 id 体系）、
	 * 不需要 {@code createdAt}（对回答无帮助，却会占 token）。
	 * <b>工具返回的字段越少，模型编造的空间越小，token 越省。</b>
	 */
	public record TaskItem(Long id, String title, String status, Integer priority, String dueDate,
			boolean overdue) {
	}

}
