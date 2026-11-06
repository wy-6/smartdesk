package com.smartdesk.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
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
import com.smartdesk.ai.dto.AgentResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.provider.ToolAwareChatModel;
import com.smartdesk.ai.tool.ToolExecutionResult;
import com.smartdesk.ai.tool.ToolRegistry;
import com.smartdesk.ai.tool.ToolSpec;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import reactor.core.publisher.Flux;

/**
 * {@link AgentLoop} 单元测试（Week4 / Day26）。
 *
 * <p><b>这个类专门验证"刹车"，不是验证"能不能跑通"。</b>
 * 正常链路在集成测试里验；这里每个用例都对应一种"会烧钱或会卡死"的故障：
 * <ul>
 *   <li>模型永远不收敛 → 轮数上限</li>
 *   <li>模型卡在同一个工具上 → 重复调用熔断</li>
 *   <li>模型一次返回一堆工具 → 单轮截断，且必须补空响应</li>
 *   <li>工具执行失败 → 回喂错误让模型自己纠正，而不是 500</li>
 * </ul>
 *
 * <p>用 StubModel 而不是 Mockito mock 接口：这里需要"按顺序返回不同响应"，
 * 用队列最直观，断言也最容易读。
 */
@DisplayName("AgentLoop：工具调用循环与保护措施")
class AgentLoopTest {

	private StubModel model;

	private ToolExecutionService toolExecutions;

	private AgentLoop loop;

	private AiProperties properties;

	@BeforeEach
	void setUp() {
		properties = new AiProperties();
		properties.getAgent().setMaxRounds(3);
		properties.getAgent().setMaxToolCallsPerRound(2);
		properties.getAgent().setDuplicateCallThreshold(2);
		properties.getAgent().setTotalTimeout(java.time.Duration.ofSeconds(30));

		model = new StubModel();
		ToolRegistry registry = mock(ToolRegistry.class);
		when(registry.all()).thenReturn(List.of(new ToolSpec("listTasks", "查询任务", Map.of(), false)));
		toolExecutions = mock(ToolExecutionService.class);
		AiCallLogService callLogs = mock(AiCallLogService.class);

		loop = new AgentLoop(model, registry, toolExecutions, new AiInvoker(properties), callLogs,
				new AiMetrics(new SimpleMeterRegistry(), properties), properties);
	}

	@Test
	@DisplayName("单轮：模型直接回答，不调用工具")
	void singleRoundAnswer() {
		model.enqueueText("你好，有什么可以帮你？");

		AgentResult result = loop.run(1L, null, "你好", List.of());

		assertThat(result.answer()).isEqualTo("你好，有什么可以帮你？");
		assertThat(result.rounds()).isEqualTo(1);
		assertThat(result.toolCalls()).isEmpty();
		assertThat(model.receivedTools.get(0)).hasSize(1);
	}

	@Test
	@DisplayName("两轮：第一轮调工具，第二轮基于结果回答")
	void twoRoundsWithTool() {
		model.enqueueToolCalls(toolCall("c1", "listTasks", "{\"onlyOverdue\":true}"));
		model.enqueueText("你有 2 条逾期任务。");
		when(toolExecutions.execute(eq("listTasks"), anyString(), any()))
				.thenReturn(ToolExecutionResult.ok("listTasks", "{\"count\":2}", 12, List.of()));

		AgentResult result = loop.run(1L, 9L, "我有什么逾期任务", List.of());

		assertThat(result.answer()).contains("2 条逾期任务");
		assertThat(result.rounds()).isEqualTo(2);
		assertThat(result.toolCalls()).hasSize(1);
		assertThat(result.toolCalls().get(0).name()).isEqualTo("listTasks");
		assertThat(result.toolCalls().get(0).ok()).isTrue();
	}

	@Test
	@DisplayName("轮数上限：模型永不收敛 → TOOL_LOOP_EXCEEDED，不会无限调用")
	void stopsAtMaxRounds() {
		// 参数不同，避免触发重复调用熔断，专门验证轮数上限这一条
		model.enqueueToolCalls(toolCall("c1", "listTasks", "{\"page\":1}"));
		model.enqueueToolCalls(toolCall("c2", "listTasks", "{\"page\":2}"));
		model.enqueueToolCalls(toolCall("c3", "listTasks", "{\"page\":3}"));
		when(toolExecutions.execute(anyString(), anyString(), any()))
				.thenReturn(ToolExecutionResult.ok("listTasks", "{}", 1, List.of()));

		assertThatThrownBy(() -> loop.run(1L, null, "查任务", List.of()))
				.isInstanceOf(AiException.class)
				.extracting(ex -> ((AiException) ex).getCode())
				.isEqualTo(AiErrorCode.TOOL_LOOP_EXCEEDED);

		// 跑了 3 轮就停，第 4 次调用不应该发生
		assertThat(model.calls).isEqualTo(3);
	}

	@Test
	@DisplayName("重复调用熔断：同一工具同一参数连续 2 次就终止（不必等到轮数上限）")
	void detectsDuplicateCalls() {
		model.enqueueToolCalls(toolCall("c1", "countTasks", "{}"));
		model.enqueueToolCalls(toolCall("c2", "countTasks", "{}"));
		model.enqueueToolCalls(toolCall("c3", "countTasks", "{}"));
		when(toolExecutions.execute(anyString(), anyString(), any()))
				.thenReturn(ToolExecutionResult.ok("countTasks", "{\"ALL\":0}", 1, List.of()));

		assertThatThrownBy(() -> loop.run(1L, null, "统计任务", List.of()))
				.isInstanceOf(AiException.class)
				.extracting(ex -> ((AiException) ex).getCode())
				.isEqualTo(AiErrorCode.TOOL_LOOP_DETECTED);

		// 第 2 轮就掐断，比轮数上限（3）更早
		assertThat(model.calls).isEqualTo(2);
		// 第 2 轮的重复调用不会被真的执行
		verify(toolExecutions, times(1)).execute(anyString(), anyString(), any());
	}

	@Test
	@DisplayName("工具失败不崩：错误回喂模型，模型仍能给出回答")
	void toolFailureDoesNotCrash() {
		model.enqueueToolCalls(toolCall("c1", "listTasks", "{\"status\":\"逾期\"}"));
		model.enqueueText("状态参数不合法，我改为查询待办任务。");
		when(toolExecutions.execute(anyString(), anyString(), any())).thenReturn(ToolExecutionResult.failure(
				"listTasks", "TOOL_ARGUMENT_INVALID", "status 必须是 TODO/IN_PROGRESS/DONE 之一", 3, List.of()));

		AgentResult result = loop.run(1L, null, "查逾期任务", List.of());

		assertThat(result.toolCalls()).hasSize(1);
		assertThat(result.toolCalls().get(0).ok()).isFalse();
		assertThat(result.toolCalls().get(0).errorCode()).isEqualTo("TOOL_ARGUMENT_INVALID");
		assertThat(result.answer()).isNotBlank();
	}

	@Test
	@DisplayName("单轮工具数截断：多出来的必须补空响应，否则上游协议会 400")
	void truncatesAndBackfillsSkippedCalls() {
		model.enqueueToolCalls(toolCall("c1", "listTasks", "{\"page\":1}"),
				toolCall("c2", "listTasks", "{\"page\":2}"), toolCall("c3", "listTasks", "{\"page\":3}"));
		model.enqueueText("已查询。");
		when(toolExecutions.execute(anyString(), anyString(), any()))
				.thenReturn(ToolExecutionResult.ok("listTasks", "{}", 1, List.of()));

		loop.run(1L, null, "查任务", List.of());

		// 上限 2，只执行 2 个
		verify(toolExecutions, times(2)).execute(anyString(), anyString(), any());
		// 但第二个 prompt 里必须有 3 条 tool 响应 —— 一条都不能少
		List<ToolResponseMessage.ToolResponse> responses = toolResponsesIn(model.receivedPrompts.get(1));
		assertThat(responses).hasSize(3);
		assertThat(responses.get(2).responseData()).contains("未执行");
	}

	@Test
	@DisplayName("空回答：模型既没调工具也没说话 → INVALID_OUTPUT")
	void rejectsEmptyAnswer() {
		model.enqueueText("   ");

		assertThatThrownBy(() -> loop.run(1L, null, "你好", List.of()))
				.isInstanceOf(AiException.class)
				.extracting(ex -> ((AiException) ex).getCode())
				.isEqualTo(AiErrorCode.INVALID_OUTPUT);
	}

	@Test
	@DisplayName("历史消息被拼在用户消息之前，system 永远在最前")
	void historyOrdering() {
		model.enqueueText("好的");
		List<Message> history = List.of(new org.springframework.ai.chat.messages.UserMessage("之前的问题"),
				new AssistantMessage("之前的回答"));

		loop.run(1L, null, "新的问题", history);

		List<Message> sent = model.receivedPrompts.get(0).getInstructions();
		assertThat(sent).hasSize(4);
		assertThat(sent.get(0).getMessageType()).isEqualTo(
				org.springframework.ai.chat.messages.MessageType.SYSTEM);
		assertThat(sent.get(1).getText()).isEqualTo("之前的问题");
		assertThat(sent.get(3).getText()).isEqualTo("新的问题");
	}

	// ------------------------------------------------------------------

	private static ToolCall toolCall(String id, String name, String arguments) {
		return new ToolCall(id, "function", name, arguments);
	}

	private static List<ToolResponseMessage.ToolResponse> toolResponsesIn(Prompt prompt) {
		for (Message message : prompt.getInstructions()) {
			if (message instanceof ToolResponseMessage toolResponse) {
				return toolResponse.getResponses();
			}
		}
		return List.of();
	}

	/** 按队列返回预设响应的假模型。 */
	static class StubModel implements ToolAwareChatModel {

		private final Deque<ChatResponse> responses = new ArrayDeque<>();

		final List<Prompt> receivedPrompts = new ArrayList<>();

		final List<List<ToolSpec>> receivedTools = new ArrayList<>();

		int calls;

		void enqueueText(String text) {
			responses.add(build(new AssistantMessage(text)));
		}

		void enqueueToolCalls(ToolCall... toolCalls) {
			AssistantMessage message = AssistantMessage.builder()
					.content(null)
					.toolCalls(List.of(toolCalls))
					.build();
			responses.add(build(message));
		}

		@Override
		public ChatResponse call(Prompt prompt, List<ToolSpec> tools) {
			calls++;
			receivedPrompts.add(prompt);
			receivedTools.add(tools);
			ChatResponse next = responses.poll();
			if (next == null) {
				throw new AiException(AiErrorCode.UPSTREAM_ERROR, "stub 没有更多预设响应");
			}
			return next;
		}

		@Override
		public ChatResponse call(Prompt prompt) {
			return call(prompt, List.of());
		}

		@Override
		public Flux<ChatResponse> stream(Prompt prompt) {
			return Flux.empty();
		}

		@Override
		public ChatOptions getOptions() {
			return ChatOptions.builder().model("stub").build();
		}

		private static ChatResponse build(AssistantMessage message) {
			return ChatResponse.builder()
					.generations(List.of(new Generation(message)))
					.metadata(ChatResponseMetadata.builder()
							.model("stub")
							.usage(new DefaultUsage(10, 5))
							.build())
					.build();
		}

	}

}
