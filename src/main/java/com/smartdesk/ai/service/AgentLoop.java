package com.smartdesk.ai.service;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Component;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.dto.AgentResult;
import com.smartdesk.ai.dto.AiMeta;
import com.smartdesk.ai.dto.ToolCallTrace;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.model.AiScene;
import com.smartdesk.ai.prompt.PromptTemplates;
import com.smartdesk.ai.provider.ToolAwareChatModel;
import com.smartdesk.ai.tool.AgentToolContext;
import com.smartdesk.ai.tool.ToolExecutionResult;
import com.smartdesk.ai.tool.ToolRegistry;
import com.smartdesk.ai.tool.ToolSpec;

/**
 * Agent 工具调用循环（Week4 / Day22、Day26）。
 *
 * <p><b>整个第 4 周的核心就是这个循环：</b>
 * <pre>
 *   messages = [system, ...history, user]
 *   repeat at most N rounds:
 *       模型调用(messages, tools)
 *       if 没有 tool_calls:  返回文本，结束
 *       for each tool_call:  执行工具，收集结果
 *       messages += assistant(tool_calls) + tool(results)
 *   超过 N 轮：终止
 * </pre>
 *
 * <p><b>为什么不用框架自带的循环？</b>
 * Spring AI 的 {@code DefaultToolCallingManager} 能跑通"基础版"，
 * 但它把轮数、单轮工具数、超时预算都写死在内部流程里。而这三件事恰恰是
 * 生产环境出问题时唯一能调的旋钮：
 * <ul>
 *   <li>模型陷入循环 → 需要"重复调用熔断"，不是简单加轮数上限</li>
 *   <li>模型一次返回 20 个 tool_calls → 需要截断，但截断后<b>必须补空响应</b>，
 *       否则 OpenAI 协议会因为"tool_calls 没有对应结果"直接 400</li>
 *   <li>4 轮 × 20s 超时 = 80s → 用户早走了，必须有整体预算</li>
 * </ul>
 * 自己写循环的代价是 200 行代码，收益是这三条全在手。
 *
 * <p><b>一个容易被忽略的正确性细节：</b>带 {@code tool_calls} 的 assistant 消息
 * 必须<b>原样</b>放回 messages（id、name、arguments 一个都不能改），
 * 紧接着是 id 一一对应的 tool 消息。顺序错了、id 对不上，上游会报错，
 * 而且错误信息往往很含糊（"invalid request"），极难排查。
 */
@Component
public class AgentLoop {

	private static final Logger log = LoggerFactory.getLogger(AgentLoop.class);

	private final ChatModel chatModel;

	private final ToolRegistry registry;

	private final ToolExecutionService toolExecutions;

	private final AiInvoker invoker;

	private final AiCallLogService callLogs;

	private final AiMetrics metrics;

	private final AiProperties properties;

	public AgentLoop(ChatModel chatModel, ToolRegistry registry, ToolExecutionService toolExecutions,
			AiInvoker invoker, AiCallLogService callLogs, AiMetrics metrics, AiProperties properties) {
		this.chatModel = chatModel;
		this.registry = registry;
		this.toolExecutions = toolExecutions;
		this.invoker = invoker;
		this.callLogs = callLogs;
		this.metrics = metrics;
		this.properties = properties;
	}

	/**
	 * 跑一轮 Agent。
	 *
	 * @param userId 当前登录用户，<b>唯一身份来源</b>
	 * @param sessionId 会话 id，可为 null
	 * @param userMessage 用户这一轮说的话
	 * @param history 历史消息（不含本轮），已按 ownerId 校验过归属
	 */
	public AgentResult run(Long userId, Long sessionId, String userMessage, List<Message> history) {
		String traceId = newTraceId();
		long startedAt = System.currentTimeMillis();
		try {
			return loop(userId, sessionId, userMessage, history, traceId, startedAt);
		}
		catch (AiException ex) {
			long latency = System.currentTimeMillis() - startedAt;
			metrics.recordFailure(AiScene.AGENT, ex.getCode(), latency);
			callLogs.recordFailure(userId, AiScene.AGENT, traceId, ex, latency);
			throw ex;
		}
	}

	private AgentResult loop(Long userId, Long sessionId, String userMessage, List<Message> history, String traceId,
			long startedAt) {
		AgentToolContext context = new AgentToolContext(userId, traceId, sessionId);
		boolean toolCapable = chatModel instanceof ToolAwareChatModel;
		List<ToolSpec> tools = toolCapable ? registry.all() : List.of();
		if (!toolCapable) {
			// 优雅降级：不支持工具的 Provider 退化成单轮问答，功能少但不 500
			log.warn("当前 ChatModel 不支持工具调用，Agent 降级为单轮问答 provider={}",
					properties.getProvider());
		}

		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(
				PromptTemplates.agentSystem(tools.stream().map(ToolSpec::name).toList())));
		if (history != null && !history.isEmpty()) {
			messages.addAll(history);
		}
		messages.add(new UserMessage(userMessage));

		int maxRounds = Math.max(1, properties.getAgent().getMaxRounds());
		int maxCalls = Math.max(1, properties.getAgent().getMaxToolCallsPerRound());
		int duplicateThreshold = Math.max(2, properties.getAgent().getDuplicateCallThreshold());
		long deadline = startedAt + properties.getAgent().getTotalTimeout().toMillis();

		List<ToolCallTrace> traces = new ArrayList<>();
		String lastSignature = null;
		int duplicates = 0;
		int totalPromptTokens = 0;
		int totalCompletionTokens = 0;
		String model = properties.getModel();

		for (int round = 1; round <= maxRounds; round++) {
			if (System.currentTimeMillis() > deadline) {
				throw new AiException(AiErrorCode.TOOL_LOOP_EXCEEDED,
						"处理超时（超过 " + properties.getAgent().getTotalTimeout().toSeconds() + " 秒），已终止");
			}

			Prompt prompt = new Prompt(new ArrayList<>(messages), options());
			long callStartedAt = System.currentTimeMillis();
			AiInvoker.Attempt<ChatResponse> attempt = invoker.execute(AiScene.AGENT,
					() -> invoke(prompt, tools));
			long callLatency = System.currentTimeMillis() - callStartedAt;

			ChatResponse response = attempt.value();
			AssistantMessage output = response == null || response.getResult() == null ? null
					: response.getResult().getOutput();
			if (output == null) {
				throw new AiException(AiErrorCode.INVALID_OUTPUT, "模型返回了空响应");
			}

			AiChatResult callResult = toResult(output, response, attempt.attempts(), callLatency, traceId);
			totalPromptTokens += callResult.promptTokens();
			totalCompletionTokens += callResult.completionTokens();
			model = callResult.model();
			metrics.recordSuccess(AiScene.AGENT, callResult);
			callLogs.recordSuccess(userId, AiScene.AGENT, callResult);

			// ---------- 收敛条件：没有工具调用就是最终回答 ----------
			if (!output.hasToolCalls()) {
				String answer = output.getText() == null ? "" : output.getText().strip();
				if (answer.isEmpty()) {
					throw new AiException(AiErrorCode.INVALID_OUTPUT, "模型没有给出回答");
				}
				return new AgentResult(answer, traceId, List.copyOf(traces), round, meta(answer, model, traceId,
						totalPromptTokens, totalCompletionTokens, System.currentTimeMillis() - startedAt));
			}

			// ---------- 执行工具 ----------
			List<ToolCall> calls = output.getToolCalls();
			List<ToolCall> toExecute = calls;
			List<ToolCall> skipped = List.of();
			if (calls.size() > maxCalls) {
				toExecute = calls.subList(0, maxCalls);
				skipped = calls.subList(maxCalls, calls.size());
				log.warn("单轮工具调用数 {} 超过上限 {}，截断执行 traceId={}", calls.size(), maxCalls, traceId);
			}

			// 必须原样回填：id / name / arguments 一个都不能改
			messages.add(output);

			List<ToolResponse> responses = new ArrayList<>(calls.size());
			for (ToolCall call : toExecute) {
				String signature = call.name() + "|" + call.arguments();
				if (signature.equals(lastSignature)) {
					duplicates++;
					if (duplicates >= duplicateThreshold) {
						// 同一个工具同一份参数连续出现 N 次，基本可以断定模型卡住了。
						// 继续跑只是烧钱，直接终止。
						throw new AiException(AiErrorCode.TOOL_LOOP_DETECTED,
								"检测到工具 " + call.name() + " 被重复调用 " + duplicates + " 次，已终止");
					}
				}
				else {
					lastSignature = signature;
					duplicates = 1;
				}

				ToolExecutionResult result = toolExecutions.execute(call.name(), call.arguments(), context);
				traces.add(ToolCallTrace.of(call.name(), call.arguments(), result.ok(), result.errorCode(),
						result.latencyMs()));
				responses.add(new ToolResponse(call.id(), call.name(), result.payload()));
			}

			// 被截断的调用必须补一条响应。少了它，OpenAI 协议会因为
			// "assistant 发了 tool_call 却没有对应 tool 消息" 报 400。
			for (ToolCall call : skipped) {
				responses.add(new ToolResponse(call.id(), call.name(), "因单轮工具调用数量超过上限，本次未执行"));
			}

			messages.add(ToolResponseMessage.builder().responses(responses).build());
		}

		throw new AiException(AiErrorCode.TOOL_LOOP_EXCEEDED, "工具调用轮数超过上限 " + maxRounds + "，已终止");
	}

	/**
	 * 调用模型，按能力选择路径。
	 *
	 * <p>为什么不直接在构造器里注入 {@link ToolAwareChatModel}？
	 * 那样一旦换成不支持工具的 Provider，应用就启动失败了。
	 * 能力判断放在运行时，换来的是"换 Provider 不会挂"。
	 */
	private ChatResponse invoke(Prompt prompt, List<ToolSpec> tools) {
		if (chatModel instanceof ToolAwareChatModel toolModel) {
			return toolModel.call(prompt, tools);
		}
		return chatModel.call(prompt);
	}

	private ChatOptions options() {
		return ChatOptions.builder()
				.model(properties.getModel())
				.temperature(properties.getTemperature())
				.build();
	}

	private AiChatResult toResult(AssistantMessage output, ChatResponse response, int attempts, long latency,
			String traceId) {
		ChatResponseMetadata metadata = response.getMetadata();
		Usage usage = metadata == null ? null : metadata.getUsage();
		int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
		int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0
				: usage.getCompletionTokens();
		String model = metadata == null || metadata.getModel() == null ? properties.getModel() : metadata.getModel();
		String content = output.getText() == null ? "" : output.getText();
		return new AiChatResult(content, AiScene.AGENT.getCode(), provider(), model, promptTokens, completionTokens,
				latency, attempts, traceId);
	}

	/**
	 * 汇总元信息。
	 *
	 * <p>注意 {@code promptTokens} / {@code completionTokens} 是<b>整轮累计</b>，
	 * 不是最后一次调用。一次三轮的 Agent 请求，成本是三次调用之和 ——
	 * 如果只报最后一次，成本报表会少算 2/3。
	 */
	private AiMeta meta(String answer, String model, String traceId, int promptTokens, int completionTokens,
			long latency) {
		AiChatResult summary = new AiChatResult(answer, AiScene.AGENT.getCode(), provider(), model, promptTokens,
				completionTokens, latency, 1, traceId);
		return AiMeta.from(summary);
	}

	private String provider() {
		return properties.getProvider() == null ? "unknown" : properties.getProvider().toLowerCase();
	}

	private static String newTraceId() {
		return UUID.randomUUID().toString().replace("-", "");
	}

}
