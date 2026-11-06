package com.smartdesk.ai.provider;

import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.prompt.PromptTemplates;

import reactor.core.publisher.Flux;

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
public class MockChatModel implements ChatModel {

	private static final Logger log = LoggerFactory.getLogger(MockChatModel.class);

	/** 模拟上游超时。 */
	public static final String FAIL_TIMEOUT = "[[FAIL:TIMEOUT]]";

	/** 模拟上游限流 429。 */
	public static final String FAIL_RATE_LIMIT = "[[FAIL:RATE_LIMIT]]";

	/** 模拟上游 5xx。 */
	public static final String FAIL_UPSTREAM = "[[FAIL:UPSTREAM]]";

	/** 模拟模型输出损坏（不是合法 JSON）。 */
	public static final String FAIL_INVALID_JSON = "[[FAIL:INVALID_JSON]]";

	private static final Pattern DUE_DATE = Pattern.compile("(\\d{4})-(\\d{1,2})-(\\d{1,2})");

	private static final Pattern SUMMARY_LIMIT = Pattern.compile("不超过 (\\d+) 字");

	private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

	private final AiProperties properties;

	public MockChatModel(AiProperties properties) {
		this.properties = properties;
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
	// 场景分发
	// ------------------------------------------------------------------

	private String render(String system, String user) {
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
