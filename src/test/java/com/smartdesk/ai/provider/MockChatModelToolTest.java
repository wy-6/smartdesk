package com.smartdesk.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.messages.ToolResponseMessage.ToolResponse;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.prompt.PromptTemplates;
import com.smartdesk.ai.tool.ToolSpec;

import tools.jackson.databind.ObjectMapper;

/**
 * Mock 的工具调用能力测试（Week4 / Day23）。
 *
 * <p><b>为什么 Mock 的工具调用也要测？</b>
 * 因为它是整个离线开发与 CI 的地基。如果 mock 的 tool_calls 格式不对，
 * 我们会去怀疑业务代码、怀疑协议适配，最后发现是"测试替身本身有问题"——
 * 这类排查极其浪费时间。<b>替身也要有契约测试。</b>
 *
 * <p>两种触发方式分别验证：
 * <ul>
 *   <li>剧本 {@code [[TOOL:xxx:{...}]]} —— 参数完全确定，用于断言</li>
 *   <li>自然语言路由 —— 演示用，验证关键词映射到正确的工具</li>
 * </ul>
 */
@DisplayName("MockChatModel：tool_calls 协议与工具结果总结")
class MockChatModelToolTest {

	private MockChatModel model;

	private List<ToolSpec> tools;

	@BeforeEach
	void setUp() {
		AiProperties properties = new AiProperties();
		properties.getMock().setLatencyMillis(0);
		model = new MockChatModel(properties, new ObjectMapper());
		tools = List.of(spec("listTasks"), spec("getTask"), spec("countTasks"), spec("createTask"));
	}

	@Test
	@DisplayName("剧本模式：直接指定工具名与参数，完全确定")
	void scriptedToolCall() {
		ChatResponse response = model.call(prompt("[[TOOL:listTasks:{\"onlyOverdue\":true}]]"), tools);

		AssistantMessage output = response.getResult().getOutput();
		assertThat(output.hasToolCalls()).isTrue();
		ToolCall call = output.getToolCalls().get(0);
		assertThat(call.name()).isEqualTo("listTasks");
		assertThat(call.arguments()).isEqualTo("{\"onlyOverdue\":true}");
		assertThat(call.id()).isNotBlank();
		assertThat(call.type()).isEqualTo("function");
	}

	@Test
	@DisplayName("自然语言路由：问逾期任务 → listTasks(onlyOverdue=true)")
	void routesOverdueQuestion() {
		ChatResponse response = model.call(prompt("我有哪些逾期的任务"), tools);

		ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
		assertThat(call.name()).isEqualTo("listTasks");
		assertThat(call.arguments()).contains("onlyOverdue");
	}

	@Test
	@DisplayName("自然语言路由：创建意图优先于查询意图")
	void routesCreateIntent() {
		ChatResponse response = model.call(prompt("帮我创建一个明天提交季度报告的任务"), tools);

		ToolCall call = response.getResult().getOutput().getToolCalls().get(0);
		// 「创建」必须先于「任务」匹配，否则会被当成查询
		assertThat(call.name()).isEqualTo("createTask");
		assertThat(call.arguments()).contains("\"title\"").contains("季度报告");
	}

	@Test
	@DisplayName("自然语言路由：统计意图 → countTasks")
	void routesCountIntent() {
		ChatResponse response = model.call(prompt("我还有多少任务没做"), tools);

		assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("countTasks");
	}

	@Test
	@DisplayName("第二轮：拿到工具结果后不再调工具，转成自然语言")
	void summarizesToolResults() {
		AssistantMessage assistant = AssistantMessage.builder()
				.content(null)
				.toolCalls(List.of(new ToolCall("c1", "function", "listTasks", "{}")))
				.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
				.responses(List.of(new ToolResponse("c1", "listTasks",
						"{\"count\":2,\"tasks\":[{\"id\":1,\"title\":\"写周报\",\"status\":\"TODO\","
								+ "\"priority\":2,\"dueDate\":\"2026-11-01 18:00\",\"overdue\":true}]}")))
				.build();

		ChatResponse response = model.call(prompt("我有哪些逾期的任务", assistant, toolResponse), tools);

		AssistantMessage output = response.getResult().getOutput();
		assertThat(output.hasToolCalls()).isFalse();
		assertThat(output.getText()).contains("写周报").contains("已逾期").contains("1 条任务");
	}

	@Test
	@DisplayName("第二轮：待确认草稿要如实说「请确认」，不能说「已创建」")
	void summarizesPendingConfirmation() {
		AssistantMessage assistant = AssistantMessage.builder()
				.content(null)
				.toolCalls(List.of(new ToolCall("c1", "function", "createTask", "{}")))
				.build();
		ToolResponseMessage toolResponse = ToolResponseMessage.builder()
				.responses(List.of(new ToolResponse("c1", "createTask",
						"{\"status\":\"PENDING_CONFIRMATION\",\"confirmationId\":\"abc123\","
								+ "\"preview\":{\"title\":\"写周报\",\"dueDate\":\"2026-11-07 18:00\"}}")))
				.build();

		String answer = model.call(prompt("帮我建一个写周报的任务", assistant, toolResponse), tools)
				.getResult().getOutput().getText();

		assertThat(answer).contains("草稿").contains("确认").contains("abc123");
	}

	@Test
	@DisplayName("死循环注入：每一轮都返回同一个工具调用")
	void loopInjection() {
		ChatResponse response = model.call(prompt("[[FAIL:LOOP]] 统计任务"), tools);

		assertThat(response.getResult().getOutput().hasToolCalls()).isTrue();
		assertThat(response.getResult().getOutput().getToolCalls().get(0).name()).isEqualTo("countTasks");
	}

	@Test
	@DisplayName("没有可用工具时退化为纯文本，不会返回 tool_calls")
	void noToolsFallsBackToText() {
		ChatResponse response = model.call(prompt("我有哪些任务"), List.of());

		assertThat(response.getResult().getOutput().hasToolCalls()).isFalse();
		assertThat(response.getResult().getOutput().getText()).isNotBlank();
	}

	// ------------------------------------------------------------------

	private static ToolSpec spec(String name) {
		return new ToolSpec(name, name, Map.of("type", "object"), name.equals("createTask"));
	}

	/** system + 用户消息 + 后续消息（assistant / tool），顺序与真实请求一致。 */
	private Prompt prompt(String user, Message... afterUser) {
		List<Message> messages = new ArrayList<>();
		messages.add(new SystemMessage(PromptTemplates.agentSystem(List.of("listTasks", "getTask", "countTasks",
				"createTask"))));
		messages.add(new UserMessage(user));
		messages.addAll(List.of(afterUser));
		return new Prompt(messages);
	}

}
