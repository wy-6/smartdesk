package com.smartdesk.ai.service;

import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.dto.AiMeta;
import com.smartdesk.ai.dto.ChatAnswer;
import com.smartdesk.ai.dto.ClassifyResponse;
import com.smartdesk.ai.dto.SummaryResponse;
import com.smartdesk.ai.dto.TaskDraftResponse;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.model.AiScene;
import com.smartdesk.ai.model.TaskCategory;
import com.smartdesk.ai.prompt.PromptTemplates;

import reactor.core.publisher.Flux;
import reactor.core.scheduler.Schedulers;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 业务实现（Week3 / Day16-20）。
 *
 * <p><b>本类的主线只有一条：模型输出是不可信输入，必须解析、校验、记录、再返回。</b>
 *
 * <p>四个场景（chat / summary / classify / task_draft）共用同一个骨架：
 * <pre>
 * 生成 Prompt
 *   → ChatClient 调用（带超时 + 有上限的重试）
 *   → 取文本 / 解析 JSON
 *   → 校验（结构化场景）
 *   → 记录调用日志 + 打指标
 *   → 返回 DTO（绝不返回实体、绝不写库）
 * </pre>
 * 差别只在"怎么解析"和"校验严不严"，这正是把 AI 能力做成后端模块的价值 ——
 * 它不是一个聊天框，而是一组有契约、有错误码、可观测的接口。
 */
@Service
public class AiServiceImpl implements AiService {

	private static final Logger log = LoggerFactory.getLogger(AiServiceImpl.class);

	private final ChatClient chatClient;

	private final AiInvoker invoker;

	private final AiCallLogService callLogService;

	private final AiMetrics metrics;

	private final AiProperties properties;

	private final TaskDraftParser draftParser;

	private final ObjectMapper objectMapper;

	public AiServiceImpl(ChatClient chatClient, AiInvoker invoker, AiCallLogService callLogService,
			AiMetrics metrics, AiProperties properties, TaskDraftParser draftParser, ObjectMapper objectMapper) {
		this.chatClient = chatClient;
		this.invoker = invoker;
		this.callLogService = callLogService;
		this.metrics = metrics;
		this.properties = properties;
		this.draftParser = draftParser;
		this.objectMapper = objectMapper;
	}

	// ------------------------------------------------------------------
	// 场景 1：自由问答（Day16）
	// ------------------------------------------------------------------

	@Override
	public ChatAnswer chat(Long userId, String message) {
		String traceId = newTraceId();
		long startedAt = System.currentTimeMillis();
		try {
			AiChatResult result = callModel(AiScene.CHAT,
					prompt(PromptTemplates.chatSystem(), message), traceId);
			String answer = result.content().strip();
			if (answer.isEmpty()) {
				throw new AiException(AiErrorCode.INVALID_OUTPUT, "模型返回了空回答");
			}
			succeed(userId, AiScene.CHAT, result);
			return new ChatAnswer(answer, AiMeta.from(result));
		}
		catch (AiException ex) {
			fail(userId, AiScene.CHAT, traceId, ex, startedAt);
			throw ex;
		}
	}

	// ------------------------------------------------------------------
	// 场景 2：摘要（Day17）
	// ------------------------------------------------------------------

	@Override
	public SummaryResponse summarize(Long userId, String text, int maxChars) {
		String traceId = newTraceId();
		long startedAt = System.currentTimeMillis();
		try {
			AiChatResult result = callModel(AiScene.SUMMARY,
					prompt(PromptTemplates.summarySystem(maxChars), text), traceId);
			String summary = result.content().strip();
			if (summary.isEmpty()) {
				throw new AiException(AiErrorCode.INVALID_OUTPUT, "模型返回了空摘要");
			}
			if (summary.length() > maxChars) {
				// 摘要属于"尽力而为"的场景：模型偶尔不守字数，截断比直接报错体验好。
				// 但结构化输出场景绝不能这么干 —— 字段被截断就是坏数据。
				log.warn("摘要超长已截断 traceId={} 实际={} 上限={}", traceId, summary.length(), maxChars);
				summary = summary.substring(0, maxChars);
			}
			succeed(userId, AiScene.SUMMARY, result);
			return new SummaryResponse(summary, maxChars, AiMeta.from(result));
		}
		catch (AiException ex) {
			fail(userId, AiScene.SUMMARY, traceId, ex, startedAt);
			throw ex;
		}
	}

	// ------------------------------------------------------------------
	// 场景 3：分类（Day17）
	// ------------------------------------------------------------------

	@Override
	public ClassifyResponse classify(Long userId, String text) {
		String traceId = newTraceId();
		long startedAt = System.currentTimeMillis();
		try {
			AiChatResult result = callModel(AiScene.CLASSIFY,
					prompt(PromptTemplates.classifySystem(), text), traceId);

			ClassifyPayload payload = parseJson(result.content(), ClassifyPayload.class, "分类结果");
			TaskCategory category = TaskCategory.fromCode(payload.category());
			if (category == null) {
				// 白名单校验：模型返回 "Bug"/"缺陷"/"BUG（疑似）" 一律拒绝，不让脏值流进业务
				throw new AiException(AiErrorCode.INVALID_OUTPUT,
						"分类不在允许范围：" + payload.category() + "，必须属于 " + PromptTemplates.CATEGORIES);
			}
			String reason = payload.reason() == null ? "" : payload.reason().strip();
			if (reason.length() > 200) {
				reason = reason.substring(0, 200);
			}
			succeed(userId, AiScene.CLASSIFY, result);
			return new ClassifyResponse(category.name(), reason, AiMeta.from(result));
		}
		catch (AiException ex) {
			fail(userId, AiScene.CLASSIFY, traceId, ex, startedAt);
			throw ex;
		}
	}

	// ------------------------------------------------------------------
	// 场景 4：任务草稿（Day18，结构化输出 + 修复重试）
	// ------------------------------------------------------------------

	@Override
	public TaskDraftResponse draftTask(Long userId, String text) {
		String traceId = newTraceId();
		long startedAt = System.currentTimeMillis();
		String lastContent = "";
		String lastErrors = "";
		int calls = 0;
		int maxRepair = Math.max(0, properties.getMaxRepairAttempts());

		for (int round = 0; round <= maxRepair; round++) {
			Prompt prompt = round == 0
					? prompt(PromptTemplates.taskDraftSystem(), text)
					: prompt(PromptTemplates.taskDraftSystem(),
							PromptTemplates.repairInstruction(lastContent, lastErrors));

			AiChatResult result;
			try {
				result = callModel(AiScene.TASK_DRAFT, prompt, traceId);
			}
			catch (AiException ex) {
				fail(userId, AiScene.TASK_DRAFT, traceId, ex, startedAt);
				throw ex;
			}
			calls++;

			TaskDraftParser.ParseResult parsed = draftParser.parse(result.content());
			if (parsed.valid()) {
				succeed(userId, AiScene.TASK_DRAFT, result);
				return new TaskDraftResponse(parsed.title(), parsed.description(), parsed.priority(),
						parsed.dueDate(), parsed.warnings(), calls, AiMeta.from(result));
			}

			lastContent = result.content();
			lastErrors = parsed.errorText();
			AiException invalid = new AiException(AiErrorCode.INVALID_OUTPUT,
					"模型输出未通过校验：" + lastErrors).withAttempts(result.attempts());
			fail(userId, AiScene.TASK_DRAFT, traceId, invalid, startedAt);

			if (round == maxRepair) {
				log.warn("结构化输出修复失败 traceId={} rounds={} errors={}", traceId, calls, lastErrors);
				throw invalid;
			}
			log.info("结构化输出不合法，回喂错误让模型修复 traceId={} errors={}", traceId, lastErrors);
		}

		// 理论上不可达：循环里一定会 return 或 throw
		throw new AiException(AiErrorCode.INVALID_OUTPUT, "模型输出未通过校验");
	}

	// ------------------------------------------------------------------
	// 场景 5：流式问答（Day20）
	// ------------------------------------------------------------------

	@Override
	public void streamChat(Long userId, String message, Consumer<String> onDelta,
			Consumer<AiChatResult> onComplete, Consumer<AiException> onError) {
		String traceId = newTraceId();
		long startedAt = System.currentTimeMillis();
		StringBuilder buffer = new StringBuilder();

		try {
			Flux<String> stream = chatClient.prompt(prompt(PromptTemplates.chatSystem(), message))
					.stream()
					.content();

			stream
					// 阻塞式的 HTTP 读取必须离开 Tomcat 工作线程，否则并发一上来就假死
					.subscribeOn(Schedulers.boundedElastic())
					.subscribe(
							delta -> {
								buffer.append(delta);
								onDelta.accept(delta);
							},
							error -> {
								AiException ex = toAiException(error);
								fail(userId, AiScene.CHAT, traceId, ex, startedAt);
								onError.accept(ex);
							},
							() -> {
								long elapsed = System.currentTimeMillis() - startedAt;
								String content = buffer.toString();
								AiChatResult result = new AiChatResult(content, AiScene.CHAT.getCode(),
										provider(), properties.getModel(), estimateTokens(message),
										estimateTokens(content), elapsed, 1, traceId);
								succeed(userId, AiScene.CHAT, result);
								onComplete.accept(result);
							});
		}
		catch (RuntimeException ex) {
			// 流还没开始就失败（例如 mock 注入的超时、Provider 未配置）
			AiException aiException = toAiException(ex);
			fail(userId, AiScene.CHAT, traceId, aiException, startedAt);
			onError.accept(aiException);
		}
	}

	// ------------------------------------------------------------------
	// 公共骨架
	// ------------------------------------------------------------------

	/**
	 * 真正的模型调用（带重试）。
	 *
	 * <p>注意它<b>不写调用记录</b>：记录必须由"结果是否可用"来决定。
	 * 模型返回了 200 但 JSON 不合法，这次调用在业务上是失败的，
	 * 如果在这里就记 success=true，报表永远看不出问题。
	 */
	private AiChatResult callModel(AiScene scene, Prompt prompt, String traceId) {
		long startedAt = System.currentTimeMillis();
		AiInvoker.Attempt<ChatResponse> attempt = invoker.execute(scene,
				() -> chatClient.prompt(prompt).call().chatResponse());
		long elapsed = System.currentTimeMillis() - startedAt;

		ChatResponse response = attempt.value();
		ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
		Usage usage = metadata == null ? null : metadata.getUsage();
		int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
		int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0
				: usage.getCompletionTokens();
		String model = metadata == null || metadata.getModel() == null ? properties.getModel() : metadata.getModel();

		return new AiChatResult(extractText(response), scene.getCode(), provider(), model, promptTokens,
				completionTokens, elapsed, attempt.attempts(), traceId);
	}

	private void succeed(Long userId, AiScene scene, AiChatResult result) {
		metrics.recordSuccess(scene, result);
		callLogService.recordSuccess(userId, scene, result);
	}

	private void fail(Long userId, AiScene scene, String traceId, AiException ex, long startedAt) {
		long latency = System.currentTimeMillis() - startedAt;
		metrics.recordFailure(scene, ex.getCode(), latency);
		callLogService.recordFailure(userId, scene, traceId, ex, latency);
	}

	/**
	 * 构建 Prompt。
	 *
	 * <p><b>为什么用 {@code Prompt} + Message 而不是 {@code chatClient.prompt().user(text)}？</b>
	 * 后者会走 Spring AI 的模板渲染（{@code {}} 是占位符）。而用户输入里完全可能带
	 * JSON、代码片段、正则 —— 一旦出现花括号，模板引擎要么报错，要么把内容吃掉。
	 * 用 Message 直接传文本，绕开模板渲染，是更安全的做法。
	 */
	private Prompt prompt(String system, String user) {
		ChatOptions options = ChatOptions.builder()
				.model(properties.getModel())
				.temperature(properties.getTemperature())
				.build();
		return new Prompt(List.of(new SystemMessage(system), new UserMessage(user)), options);
	}

	private <T> T parseJson(String content, Class<T> type, String what) {
		String json = JsonContents.extractObject(content);
		if (json == null) {
			throw new AiException(AiErrorCode.INVALID_OUTPUT, what + "中没有找到 JSON 对象");
		}
		try {
			T value = objectMapper.readValue(json, type);
			if (value == null) {
				throw new AiException(AiErrorCode.INVALID_OUTPUT, what + "解析结果为空");
			}
			return value;
		}
		catch (AiException ex) {
			throw ex;
		}
		catch (RuntimeException ex) {
			throw new AiException(AiErrorCode.INVALID_OUTPUT, what + "不是合法 JSON：" + ex.getMessage(), ex);
		}
	}

	private static String extractText(ChatResponse response) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			return "";
		}
		String text = response.getResult().getOutput().getText();
		return text == null ? "" : text;
	}

	private static AiException toAiException(Throwable error) {
		if (error instanceof AiException aiException) {
			return aiException;
		}
		return new AiException(AiErrorCode.UPSTREAM_ERROR, "模型调用失败：" + error.getMessage(), error);
	}

	private String provider() {
		return properties.getProvider() == null ? "unknown" : properties.getProvider().toLowerCase();
	}

	private static String newTraceId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

	/** 流式场景拿不到精确用量，按字符粗估（2 字符 ≈ 1 Token）。 */
	private static int estimateTokens(String text) {
		return text == null || text.isEmpty() ? 0 : Math.max(1, text.length() / 2);
	}

	/** 分类结果的 JSON 结构。public 是为了让 Jackson 能反射实例化。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ClassifyPayload(String category, String reason) {
	}

}
