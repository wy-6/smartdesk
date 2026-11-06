package com.smartdesk.ai.provider;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.prompt.PromptTemplates;
import com.smartdesk.ai.tool.ToolSpec;

import reactor.core.publisher.Flux;
import tools.jackson.databind.ObjectMapper;

/**
 * 离线 Mock 模型（Week3 / Day16）。
 *
 * <p><b>为什么一定要有 mock Provider？</b>这不是"偷懒不接真模型"，而是工程必需：
 * <ol>
 *   <li><b>测试可重复</b>：真实模型有随机性，同一句话两次结果不同，测试会变成掷骰子</li>
 *   <li><b>测试不烧钱</b>：CI 每次跑几十个用例，真模型就是真账单</li>
 *   <li><b>故障可注入</b>：超时、限流、5xx 在真实环境很难稳定复现，
 *       但 Day19 要求"模拟超时和错误响应"，mock 才能做到</li>
 *   <li><b>没有 Key 也能开发</b>：面试官 clone 下来就能跑，不需要你的账号</li>
 * </ol>
 *
 * <p><b>它不是"假数据生成器"，而是一个规则引擎</b>：按 Prompt 里的场景标记
 * 走不同分支，输出<b>由输入决定</b>的结果。所以断言的是"行为正确"，
 * 不是"文案一致"。
 *
 * <p><b>故障注入方式</b>：用户消息里带下面这些标记，mock 就会抛出对应错误。
 * 真实 Provider 不会识别它们（会当成普通文本），所以这段逻辑只影响本地与测试。
 */
public class MockChatModel implements ToolAwareChatModel {

	private static final Logger log = LoggerFactory.getLogger(MockChatModel.class);

	/** 模拟上游超时。 */
	public static final String FAIL_TIMEOUT = "[[FAIL:TIMEOUT]]";

	/** 模拟上游限流 429。 */
	public static final String FAIL_RATE_LIMIT = "[[FAIL:RATE_LIMIT]]";

	/** 模拟上游 5xx。 */
	public static final String FAIL_UPSTREAM = "[[FAIL:UPSTREAM]]";

	/** 模拟模型输出损坏（不是合法 JSON）。 */
	public static final String FAIL_INVALID_JSON = "[[FAIL:INVALID_JSON]]";

	/**
	 * 模拟"模型陷入死循环"（Week4 / Day26）。
	 *
	 * <p>带上这个标记后，mock 每一轮都会返回同一个 {@code countTasks} 调用，
	 * 永不给出最终答案。用来验证重复调用熔断和轮数上限真的生效 ——
	 * 这类保护措施如果不写测试，等于没有。
	 */
	public static final String FAIL_LOOP = "[[FAIL:LOOP]]";

	/** 剧本模式的工具调用标记：{@code [[TOOL:工具名:JSON参数]]}。 */
	private static final Pattern TOOL_SCRIPT = Pattern.compile("\\[\\[TOOL:([A-Za-z0-9_]+)(?::(.*?))?\\]\\]");

	private static final Pattern TASK_ID = Pattern.compile("(?:任务|task)\\s*#?\\s*(\\d{1,12})", Pattern.CASE_INSENSITIVE);

	private static final List<String> TITLE_NOISE = List.of("帮我", "请", "麻烦", "创建", "新建", "建一个", "加一个",
			"记一下", "提醒我", "一个任务", "一条任务", "这个任务", "任务", "待办", "，", ",", "。", "！", "!");

	private static final Pattern DUE_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

	private static final Pattern SUMMARY_LIMIT = Pattern.compile("不超过 (\\d+) 字");

	private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	private final AiProperties properties;

	private final ObjectMapper objectMapper;

	public MockChatModel(AiProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		String system = text(prompt.getSystemMessage());
		String user = text(prompt.getUserMessage());

		sleep(properties.getMock().getLatencyMillis());
		failIfRequested(user);

		String content = render(system, user);
		return response(content, prompt);
	}

	/**
	 * 带工具定义的调用（Week4 / Day23）—— 离线 Agent 的核心。
	 *
	 * <p><b>它必须能模拟三种行为，否则第 4 周没法离线开发：</b>
	 * <ol>
	 *   <li><b>决定调用工具</b>：返回 {@code tool_calls} 而不是文本</li>
	 *   <li><b>看懂工具结果</b>：第二轮拿到 {@code ToolResponseMessage} 后，
	 *       把 JSON 转成人话（真实模型做的是同一件事，只是靠语义理解）</li>
	 *   <li><b>犯错</b>：编不存在的枚举值、传错类型、陷入死循环 ——
	 *       这是 Agent 最容易出问题的地方，必须能稳定复现</li>
	 * </ol>
	 *
	 * <p><b>两种触发方式，各有用途：</b>
	 * <ul>
	 *   <li><b>自然语言路由</b>（"我有什么逾期任务"）：演示用，让整个链路看起来像真的</li>
	 *   <li><b>剧本标记</b> {@code [[TOOL:listTasks:{"onlyOverdue":true}]]}：测试用，
	 *       参数完全确定，断言才可靠。自然语言路由会随关键词表变化，不适合做断言</li>
	 * </ul>
	 *
	 * <p>这不是"假 AI"，而是<b>把模型的决策过程外化成可枚举的规则</b>。
	 * 真实模型是"给定 Prompt 输出 token"，mock 是"给定 Prompt 选一个分支" ——
	 * 对上层来说，接口行为完全一致。
	 */
	@Override
	public ChatResponse call(Prompt prompt, List<ToolSpec> tools) {
		String user = lastUserText(prompt);
		sleep(properties.getMock().getLatencyMillis());
		failIfRequested(user);

		// 1. 死循环注入：每一轮都返回同一个工具调用，永不收敛
		if (user.contains(FAIL_LOOP) && has(tools, "countTasks")) {
			return toolCallResponse("countTasks", "{}", prompt);
		}

		// 2. 已经拿到工具结果 → 生成最终回答（真实模型的"第二轮"）
		List<ToolResponseMessage.ToolResponse> responses = toolResponses(prompt);
		if (!responses.isEmpty()) {
			return response(renderToolSummary(responses), prompt);
		}

		// 3. 没有工具可用 → 退化成普通问答
		if (tools == null || tools.isEmpty()) {
			return response(render(text(prompt.getSystemMessage()), user), prompt);
		}

		// 4. 剧本模式：参数完全由测试指定
		ToolInvocation scripted = parseScript(user);
		if (scripted != null && has(tools, scripted.name())) {
			return toolCallResponse(scripted.name(), scripted.arguments(), prompt);
		}

		// 5. 自然语言路由：让演示看起来像真的
		ToolInvocation routed = route(user, tools);
		if (routed != null) {
			return toolCallResponse(routed.name(), routed.arguments(), prompt);
		}

		return response(render(text(prompt.getSystemMessage()), user), prompt);
	}

	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		String system = text(prompt.getSystemMessage());
		String user = text(prompt.getUserMessage());
		failIfRequested(user);

		String content = render(system, user);
		List<String> chunks = split(content, 6);
		long delay = properties.getMockChunkDelayMillis();
		Flux<String> flux = Flux.fromIterable(chunks);
		if (delay > 0) {
			flux = flux.delayElements(Duration.ofMillis(delay));
		}
		return flux.map(chunk -> response(chunk, prompt));
	}

	@Override
	public ChatOptions getOptions() {
		return ChatOptions.builder()
				.model("mock-rule-v1")
				.temperature(properties.getTemperature())
				.build();
	}

	// ------------------------------------------------------------------
	// 工具调用（Week4）
	// ------------------------------------------------------------------

	/** 取最后一条用户消息。历史里可能有多条 user，只有最新那条是本次问题。 */
	private static String lastUserText(Prompt prompt) {
		String last = "";
		for (Message message : prompt.getInstructions()) {
			if (message.getMessageType() == org.springframework.ai.chat.messages.MessageType.USER) {
				last = text(message);
			}
		}
		return last;
	}

	/** 取最后一批工具结果。多轮场景下前面几轮的旧结果要跳过。 */
	private static List<ToolResponseMessage.ToolResponse> toolResponses(Prompt prompt) {
		List<ToolResponseMessage.ToolResponse> last = List.of();
		for (Message message : prompt.getInstructions()) {
			if (message instanceof ToolResponseMessage toolResponse) {
				last = toolResponse.getResponses();
			}
		}
		return last;
	}

	private static boolean has(List<ToolSpec> tools, String name) {
		return tools != null && tools.stream().anyMatch(tool -> tool.name().equals(name));
	}

	private static ToolInvocation parseScript(String user) {
		Matcher matcher = TOOL_SCRIPT.matcher(user);
		if (!matcher.find()) {
			return null;
		}
		String arguments = matcher.group(2);
		return new ToolInvocation(matcher.group(1), arguments == null || arguments.isBlank() ? "{}" : arguments.strip());
	}

	/**
	 * 自然语言 → 工具调用。
	 *
	 * <p><b>顺序很重要：</b>"创建"必须先于"列表"，否则"帮我建一个任务"会被
	 * 匹配成"查询任务"。这类优先级 bug 在真实 Prompt 里同样存在 ——
	 * 工具描述写得越像，模型越容易选错。
	 */
	private ToolInvocation route(String user, List<ToolSpec> tools) {
		if (!containsAny(user, "任务", "待办", "todo", "TODO", "逾期", "过期")) {
			return null;
		}
		if (containsAny(user, "创建", "新建", "建一个", "加一个", "记一下", "提醒我", "安排") && has(tools, "createTask")) {
			return new ToolInvocation("createTask", createTaskArguments(user));
		}
		if (containsAny(user, "多少", "几个", "几件", "统计", "数量") && has(tools, "countTasks")) {
			return new ToolInvocation("countTasks", "{}");
		}
		Matcher id = TASK_ID.matcher(user);
		if (id.find() && has(tools, "getTask")) {
			return new ToolInvocation("getTask", "{\"taskId\":" + id.group(1) + "}");
		}
		if (containsAny(user, "逾期", "超期", "过期", "快到期") && has(tools, "listTasks")) {
			return new ToolInvocation("listTasks", "{\"onlyOverdue\":true,\"limit\":5}");
		}
		if (has(tools, "listTasks")) {
			return new ToolInvocation("listTasks", "{\"limit\":5}");
		}
		return null;
	}

	private String createTaskArguments(String user) {
		Map<String, Object> arguments = new LinkedHashMap<>();
		arguments.put("title", extractTitle(user));
		String dueDate = extractDueDate(user);
		if (dueDate != null) {
			arguments.put("dueDate", dueDate);
		}
		if (containsAny(user, "紧急", "立刻", "马上", "最高优先")) {
			arguments.put("priority", 5);
		}
		else if (containsAny(user, "重要", "尽快", "高优先")) {
			arguments.put("priority", 4);
		}
		return toJson(arguments);
	}

	/** 从"帮我明天前创建一个提交季度报告的任务"里扒出标题。 */
	private String extractTitle(String text) {
		String title = text;
		for (String noise : TITLE_NOISE) {
			title = title.replace(noise, " ");
		}
		title = title.replaceAll("(今天|明天|后天|大后天|上午|下午|晚上)", " ");
		title = title.replaceAll("\\d{4}-\\d{1,2}-\\d{1,2}", " ");
		title = title.replaceAll("\\d{1,2}\\s*(点|时)", " ");
		title = title.replaceAll("\\s+", " ").strip();
		return title.isEmpty() ? "新任务" : title.length() > 60 ? title.substring(0, 60) : title;
	}

	/**
	 * 把工具结果翻成人话。
	 *
	 * <p>真实模型做的是"读 JSON 再理解"，这里做的是"按工具名查表"。
	 * 差别只在聪明程度，<b>链路上的位置完全一样</b>：
	 * 都是把 tool message 转成面向用户的自然语言。
	 */
	private String renderToolSummary(List<ToolResponseMessage.ToolResponse> responses) {
		StringBuilder summary = new StringBuilder();
		for (ToolResponseMessage.ToolResponse response : responses) {
			summary.append(describe(response.name(), response.responseData()));
		}
		return summary.isEmpty() ? "（mock 模式）工具已执行，但没有返回内容。" : "（mock 模式）" + summary;
	}

	private String describe(String toolName, String payload) {
		Map<String, Object> data;
		try {
			@SuppressWarnings("unchecked")
			Map<String, Object> parsed = objectMapper.readValue(payload, Map.class);
			data = parsed;
		}
		catch (RuntimeException ex) {
			// 工具返回了失败文案（不是 JSON）—— 直接转述，和真实模型的行为一致
			return abbreviate(payload, 200) + "\n";
		}
		if (data == null) {
			return abbreviate(payload, 200) + "\n";
		}
		return switch (toolName) {
			case "listTasks" -> describeTasks(data);
			case "getTask" -> describeTask(data);
			case "countTasks" -> describeCounts(data);
			case "createTask" -> describeCreate(data);
			default -> "工具 " + toolName + " 返回：" + abbreviate(payload, 150) + "\n";
		};
	}

	private String describeTasks(Map<String, Object> data) {
		Object tasks = data.get("tasks");
		if (!(tasks instanceof List<?> list) || list.isEmpty()) {
			Object message = data.get("message");
			return (message == null ? "没有符合条件的任务。" : String.valueOf(message)) + "\n";
		}
		StringBuilder text = new StringBuilder("我查到 " + list.size() + " 条任务：\n");
		int index = 1;
		for (Object item : list) {
			if (!(item instanceof Map<?, ?> task)) {
				continue;
			}
			text.append(index++).append(". 《").append(task.get("title")).append("》")
					.append("，状态 ").append(statusLabel(task.get("status")));
			if (task.get("dueDate") != null) {
				text.append("，截止 ").append(task.get("dueDate"));
			}
			if (Boolean.TRUE.equals(task.get("overdue"))) {
				text.append("（已逾期）");
			}
			text.append('\n');
		}
		return text.toString();
	}

	private String describeTask(Map<String, Object> data) {
		if (!Boolean.TRUE.equals(data.get("found"))) {
			return String.valueOf(data.getOrDefault("message", "没有找到这个任务。")) + "\n";
		}
		Object task = data.get("task");
		if (!(task instanceof Map<?, ?> item)) {
			return "找到了任务，但内容无法解析。\n";
		}
		StringBuilder text = new StringBuilder("《" + item.get("title") + "》，状态 " + statusLabel(item.get("status"))
				+ "，优先级 " + item.get("priority"));
		if (item.get("dueDate") != null) {
			text.append("，截止 ").append(item.get("dueDate"));
		}
		if (Boolean.TRUE.equals(item.get("overdue"))) {
			text.append("，已逾期");
		}
		return text.append("。\n").toString();
	}

	private String describeCounts(Map<String, Object> data) {
		return "当前任务统计：待办 " + data.getOrDefault("TODO", 0) + " 条，进行中 "
				+ data.getOrDefault("IN_PROGRESS", 0) + " 条，已完成 " + data.getOrDefault("DONE", 0)
				+ " 条，已取消 " + data.getOrDefault("CANCELLED", 0) + " 条，共 " + data.getOrDefault("ALL", 0)
				+ " 条。\n";
	}

	private String describeCreate(Map<String, Object> data) {
		if (!"PENDING_CONFIRMATION".equals(data.get("status"))) {
			return "创建任务的结果：" + abbreviate(String.valueOf(data), 150) + "\n";
		}
		Object preview = data.get("preview");
		String title = preview instanceof Map<?, ?> map ? String.valueOf(map.get("title")) : "未命名";
		String due = preview instanceof Map<?, ?> map && map.get("dueDate") != null
				? "，截止 " + map.get("dueDate") : "";
		return "已生成任务草稿《" + title + "》" + due + "。确认号 "
				+ data.get("confirmationId") + "，请在界面上点确认后才会真正创建。\n";
	}

	private static String statusLabel(Object status) {
		if (status == null) {
			return "未知";
		}
		return switch (String.valueOf(status)) {
			case "TODO" -> "待办";
			case "IN_PROGRESS" -> "进行中";
			case "DONE" -> "已完成";
			case "CANCELLED" -> "已取消";
			default -> String.valueOf(status);
		};
	}

	private ChatResponse toolCallResponse(String name, String arguments, Prompt prompt) {
		ToolCall call = new ToolCall("call_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8), "function",
				name, arguments);
		AssistantMessage message = AssistantMessage.builder().content(null).toolCalls(List.of(call)).build();
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
				.model("mock-rule-v1")
				// 工具调用的输出 token 很少（就是一个函数名 + 参数），按实际内容估算
				.usage(new DefaultUsage(estimateTokens(joinPrompt(prompt)), estimateTokens(arguments)))
				.build();
		return ChatResponse.builder()
				.generations(List.of(new Generation(message)))
				.metadata(metadata)
				.build();
	}

	private static String abbreviate(String text, int limit) {
		if (text == null) {
			return "";
		}
		String single = text.replaceAll("\\s+", " ").strip();
		return single.length() > limit ? single.substring(0, limit) + "…" : single;
	}

	private record ToolInvocation(String name, String arguments) {
	}

	// ------------------------------------------------------------------
	// 场景分发
	// ------------------------------------------------------------------

	private String render(String system, String user) {
		if (system.contains(PromptTemplates.AGENT_MARKER)) {
			return renderAgentChat(user);
		}
		if (system.contains(PromptTemplates.TASK_DRAFT_MARKER)) {
			if (user.contains(FAIL_INVALID_JSON)) {
				// 模拟"模型不听话，返回了一段解释而不是 JSON"
				return "抱歉，我不太确定你的意思，能再具体一点吗？";
			}
			return renderTaskDraft(user);
		}
		if (system.contains(PromptTemplates.CLASSIFY_MARKER)) {
			if (user.contains(FAIL_INVALID_JSON)) {
				return "这个应该算 bug 吧。";
			}
			return renderClassification(user);
		}
		if (system.contains(PromptTemplates.SUMMARY_MARKER)) {
			return renderSummary(system, user);
		}
		return renderChat(user);
	}

	/** Agent 场景下没命中任何工具时的兜底回答。 */
	private String renderAgentChat(String user) {
		String brief = user.strip();
		if (brief.isEmpty()) {
			return "你想让我做什么？比如「我有哪些逾期任务」，或者「帮我创建一个明天提交报告的任务」。";
		}
		return "（mock 模式）我理解你想「" + abbreviate(brief, 40) + "」，但没有命中可用的工具。"
				+ "可以试试问我：我有哪些逾期任务 / 我还有多少任务没做 / 帮我创建一个明天提交报告的任务。";
	}

	/** 摘要：截断原文并加标记，长度遵守系统提示词里的上限。 */
	private String renderSummary(String system, String user) {
		String normalized = user.replaceAll("\\s+", " ").strip();
		int limit = 120;
		Matcher matcher = SUMMARY_LIMIT.matcher(system);
		if (matcher.find()) {
			limit = Integer.parseInt(matcher.group(1));
		}
		String body = normalized.length() <= limit ? normalized : normalized.substring(0, limit) + "…";
		return body.isBlank() ? "（原文为空，无可摘要内容）" : body;
	}

	/** 分类：按关键词映射到固定分类，输出严格 JSON。 */
	private String renderClassification(String user) {
		String category;
		String reason;
		if (containsAny(user, "报错", "异常", "崩溃", "失败", "打不开", "500")) {
			category = "BUG";
			reason = "描述中包含故障现象关键词";
		}
		else if (containsAny(user, "发票", "账单", "退款", "扣费", "支付")) {
			category = "BILLING";
			reason = "描述涉及账单与支付";
		}
		else if (containsAny(user, "登录", "密码", "账号", "权限")) {
			category = "ACCOUNT";
			reason = "描述涉及账号与登录";
		}
		else if (containsAny(user, "需求", "希望支持", "新增功能", "能不能加")) {
			category = "FEATURE";
			reason = "描述是功能诉求";
		}
		else {
			category = "OTHER";
			reason = "未命中任何已知分类";
		}
		Map<String, Object> json = new LinkedHashMap<>();
		json.put("category", category);
		json.put("reason", reason);
		return toJson(json);
	}

	/**
	 * 任务提取：一个极简规则解析器。
	 *
	 * <p>它当然不如大模型聪明，但足够验证"结构化输出 → 校验 → 落库前置检查"整条链路。
	 * 真实模型下同样的 Prompt 会给出更好的标题和描述，接口契约完全一致。
	 */
	private String renderTaskDraft(String user) {
		String text = user.strip();
		Map<String, Object> json = new LinkedHashMap<>();

		if (text.isEmpty()) {
			json.put("title", "");
			json.put("description", null);
			json.put("priority", 3);
			json.put("dueDate", null);
			return toJson(json);
		}

		String title = text.split("[，,。;；\\n]")[0].strip();
		if (title.length() > 60) {
			title = title.substring(0, 60);
		}
		json.put("title", title);
		json.put("description", text.length() > 60 ? text : null);

		int priority = 3;
		if (containsAny(text, "紧急", "立刻", "马上", "最高优先")) {
			priority = 5;
		}
		else if (containsAny(text, "重要", "尽快", "高优先")) {
			priority = 4;
		}
		else if (containsAny(text, "不急", "低优先", "有空")) {
			priority = 1;
		}
		json.put("priority", priority);

		json.put("dueDate", extractDueDate(text));
		return toJson(json);
	}

	/** 日期解析：后天必须先于明天判断，否则 "后天" 会被 "明天" 截胡。 */
	private String extractDueDate(String text) {
		LocalDate today = LocalDate.now(ZONE);
		Matcher matcher = DUE_DATE.matcher(text);
		if (matcher.find()) {
			try {
				return LocalDate.of(Integer.parseInt(matcher.group(1)), Integer.parseInt(matcher.group(2)),
						Integer.parseInt(matcher.group(3))).atTime(18, 0).toString();
			}
			catch (RuntimeException ex) {
				return null;
			}
		}
		LocalDateTime due = null;
		if (text.contains("后天")) {
			due = today.plusDays(2).atTime(18, 0);
		}
		else if (text.contains("明天")) {
			due = today.plusDays(1).atTime(18, 0);
		}
		else if (text.contains("今天")) {
			due = today.atTime(18, 0);
		}
		return due == null ? null : due.toString();
	}

	private String renderChat(String user) {
		String trimmed = user.strip();
		if (trimmed.isEmpty()) {
			return "你还没有输入问题。";
		}
		String brief = trimmed.length() > 60 ? trimmed.substring(0, 60) + "…" : trimmed;
		log.debug("mock chat 输入长度={}", trimmed.length());
		return "（mock 模式）我收到了你的问题：「" + brief + "」。"
				+ "当前没有接入真实模型，这条回复用于验证调用链路、超时重试和调用日志。"
				+ "把 AI_PROVIDER 设为 openai 并配置 API Key 后即可切换为真实模型，业务代码无需改动。";
	}

	// ------------------------------------------------------------------
	// 基础设施
	// ------------------------------------------------------------------

	private void failIfRequested(String user) {
		if (user.contains(FAIL_TIMEOUT)) {
			// 直接抛而不是真的 sleep 20s：测试要快，语义等价
			throw new AiException(AiErrorCode.TIMEOUT, "mock 注入：上游超时");
		}
		if (user.contains(FAIL_RATE_LIMIT)) {
			throw new AiException(AiErrorCode.RATE_LIMITED, "mock 注入：上游限流 429");
		}
		if (user.contains(FAIL_UPSTREAM)) {
			throw new AiException(AiErrorCode.UPSTREAM_ERROR, "mock 注入：上游 500");
		}
	}

	private ChatResponse response(String content, Prompt prompt) {
		int promptTokens = estimateTokens(joinPrompt(prompt));
		int completionTokens = estimateTokens(content);
		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
				.model("mock-rule-v1")
				.usage(new DefaultUsage(promptTokens, completionTokens))
				.build();
		return ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage(content))))
				.metadata(metadata)
				.build();
	}

	private String joinPrompt(Prompt prompt) {
		List<String> parts = new ArrayList<>();
		for (Message message : prompt.getInstructions()) {
			parts.add(text(message));
		}
		return String.join("\n", parts);
	}

	private static String text(Message message) {
		return message == null || message.getText() == null ? "" : message.getText();
	}

	/**
	 * 粗估 Token。
	 *
	 * <p>中文大约 1 字 ≈ 1 Token，英文 1 词 ≈ 1.3 Token，这里统一按"2 字符 ≈ 1 Token"
	 * 保守估算。真实 Provider 会返回精确用量，mock 只是让报表不为空。
	 */
	private static int estimateTokens(String text) {
		if (text == null || text.isEmpty()) {
			return 0;
		}
		return Math.max(1, text.length() / 2);
	}

	private static boolean containsAny(String text, String... keywords) {
		for (String keyword : keywords) {
			if (text.contains(keyword)) {
				return true;
			}
		}
		return false;
	}

	private String toJson(Map<String, Object> map) {
		StringBuilder sb = new StringBuilder("{");
		boolean first = true;
		for (Map.Entry<String, Object> entry : map.entrySet()) {
			if (!first) {
				sb.append(',');
			}
			first = false;
			sb.append('"').append(entry.getKey()).append("\":");
			Object value = entry.getValue();
			if (value == null) {
				sb.append("null");
			}
			else if (value instanceof Number) {
				sb.append(value);
			}
			else {
				sb.append('"').append(String.valueOf(value).replace("\\", "\\\\").replace("\"", "\\\""))
						.append('"');
			}
		}
		return sb.append('}').toString();
	}

	private static List<String> split(String text, int size) {
		List<String> chunks = new ArrayList<>();
		for (int i = 0; i < text.length(); i += size) {
			chunks.add(text.substring(i, Math.min(text.length(), i + size)));
		}
		return chunks.isEmpty() ? List.of("") : chunks;
	}

	private static void sleep(long millis) {
		if (millis <= 0) {
			return;
		}
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
		}
	}

}
