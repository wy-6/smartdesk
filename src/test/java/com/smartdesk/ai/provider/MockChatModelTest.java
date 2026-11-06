package com.smartdesk.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.prompt.PromptTemplates;

/**
 * Mock 模型测试（Week3 / Day16-Day19）。
 *
 * <p>这些测试验证的是"离线 Provider 的行为契约"，
 * 不是"某句文案"。真实模型接上以后，上层 Service 的测试依然成立 ——
 * 因为大家都实现了同一个 {@code ChatModel} 接口。
 */
class MockChatModelTest {

	private final MockChatModel model = new MockChatModel(properties());

	private static AiProperties properties() {
		AiProperties properties = new AiProperties();
		properties.getMock().setLatencyMillis(0);
		properties.setMockChunkDelayMillis(0);
		return properties;
	}

	private Prompt prompt(String system, String user) {
		return new Prompt(List.of(new SystemMessage(system), new UserMessage(user)));
	}

	private String call(String system, String user) {
		ChatResponse response = model.call(prompt(system, user));
		return response.getResult().getOutput().getText();
	}

	@Test
	@DisplayName("任务提取：应输出可解析 JSON，并按关键词给出优先级")
	void taskDraft_shouldReturnJsonWithPriority() {
		String json = call(PromptTemplates.taskDraftSystem(), "明天下午把季度报告写完，这个比较重要");

		assertThat(json).contains("\"title\"").contains("\"priority\":4").contains("\"dueDate\"");
		assertThat(json).doesNotContain("```");
	}

	@Test
	@DisplayName("任务提取：'紧急' 应映射为最高优先级 5")
	void taskDraft_urgent_shouldMapToPriority5() {
		String json = call(PromptTemplates.taskDraftSystem(), "紧急处理线上支付回调失败");

		assertThat(json).contains("\"priority\":5");
	}

	@Test
	@DisplayName("分类：账单相关问题应归到 BILLING")
	void classify_billing_shouldReturnBilling() {
		String json = call(PromptTemplates.classifySystem(), "这个月账单扣费金额不对");

		assertThat(json).contains("\"category\":\"BILLING\"");
	}

	@Test
	@DisplayName("摘要：输出长度不得超过提示词里的上限")
	void summary_shouldRespectLimit() {
		String summary = call(PromptTemplates.summarySystem(30), "很长的原文".repeat(50));

		assertThat(summary.length()).isLessThanOrEqualTo(31);
	}

	@Test
	@DisplayName("故障注入：超时标记应抛 AI_TIMEOUT")
	void timeoutInjection_shouldThrowTimeout() {
		assertThatThrownBy(() -> call(PromptTemplates.chatSystem(), MockChatModel.FAIL_TIMEOUT + " 你好"))
				.isInstanceOfSatisfying(AiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo(AiErrorCode.TIMEOUT));
	}

	@Test
	@DisplayName("故障注入：限流标记应抛 AI_RATE_LIMITED")
	void rateLimitInjection_shouldThrowRateLimited() {
		assertThatThrownBy(() -> call(PromptTemplates.chatSystem(), MockChatModel.FAIL_RATE_LIMIT))
				.isInstanceOfSatisfying(AiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo(AiErrorCode.RATE_LIMITED));
	}

	@Test
	@DisplayName("流式：分片拼接后应与一次性调用一致")
	void stream_shouldConcatToSameContent() {
		Prompt prompt = prompt(PromptTemplates.chatSystem(), "介绍一下你自己的能力");

		String joined = String.join("", model.stream(prompt)
				.map(response -> response.getResult().getOutput().getText())
				.collectList()
				.block());

		assertThat(joined).isEqualTo(call(PromptTemplates.chatSystem(), "介绍一下你自己的能力"));
	}

}
