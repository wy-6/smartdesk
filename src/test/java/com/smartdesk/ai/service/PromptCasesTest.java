package com.smartdesk.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.dto.ClassifyResponse;
import com.smartdesk.ai.dto.SummaryResponse;
import com.smartdesk.ai.dto.TaskDraftResponse;
import com.smartdesk.ai.provider.MockChatModel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Prompt 回归评测（Week3 / Day17）。
 *
 * <p><b>为什么要有这组测试？</b>
 * Prompt 改动是"没有编译器保护"的改动：改一句话，可能让分类全错、让摘要超长，
 * 而且不会报错。唯一的办法就是准备一批固定输入，每次改 Prompt 都跑一遍。
 *
 * <p>本组用例共 15 条（4 条摘要 + 5 条分类 + 6 条任务提取），
 * 断言的是<b>结构与约束</b>（分类在白名单内、优先级在 1-5、摘要不超字数），
 * 而不是具体文案 —— 否则接上真实模型后测试必然全红。
 *
 * <p>真实模型接入后，同一批用例可以直接复用，只是把 mock Provider 换成 openai。
 * 这就是"评测集"和"单元测试"的区别：<b>单元测试保证代码不错，评测集衡量效果好坏。</b>
 */
class PromptCasesTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private AiService service;

	@BeforeEach
	void setUp() {
		AiProperties properties = new AiProperties();
		properties.setProvider("mock");
		properties.setMaxAttempts(2);
		properties.setRetryBaseMillis(1);
		properties.getMock().setLatencyMillis(0);

		service = new AiServiceImpl(ChatClient.create(new MockChatModel(properties, new tools.jackson.databind.ObjectMapper())), new AiInvoker(properties),
				mock(AiCallLogService.class), new AiMetrics(new SimpleMeterRegistry(), properties), properties,
				new TaskDraftParser(objectMapper), objectMapper);
	}

	@Test
	@DisplayName("15 条 Prompt 用例应全部落在约束范围内")
	void allCases_shouldRespectConstraints() throws Exception {
		JsonNode cases = objectMapper.readTree(readResource("ai/prompt-cases.json"));
		assertThat(cases).hasSize(15);

		List<String> failures = new ArrayList<>();
		for (JsonNode item : cases) {
			String id = item.path("id").asText();
			String scene = item.path("scene").asText();
			String input = item.path("input").asText();

			switch (scene) {
				case "summary" -> {
					int maxChars = item.path("maxChars").asInt(120);
					SummaryResponse response = service.summarize(1L, input, maxChars);
					if (response.summary().isBlank() || response.summary().length() > maxChars) {
						failures.add(id + ": 摘要长度 " + response.summary().length() + " 超过 " + maxChars);
					}
				}
				case "classify" -> {
					String expected = item.path("expectedCategory").asText();
					ClassifyResponse response = service.classify(1L, input);
					if (!expected.equals(response.category())) {
						failures.add(id + ": 期望 " + expected + "，实际 " + response.category());
					}
				}
				case "task_draft" -> {
					TaskDraftResponse draft = service.draftTask(1L, input);
					int expectedPriority = item.path("expectedPriority").asInt(3);
					boolean expectDueDate = item.path("expectDueDate").asBoolean(false);
					if (draft.priority() != expectedPriority) {
						failures.add(id + ": 期望优先级 " + expectedPriority + "，实际 " + draft.priority());
					}
					if (expectDueDate != (draft.dueDate() != null)) {
						failures.add(id + ": 截止时间期望 " + expectDueDate + "，实际 " + draft.dueDate());
					}
					if (draft.title().isBlank()) {
						failures.add(id + ": 标题为空");
					}
				}
				default -> failures.add(id + ": 未知场景 " + scene);
			}
		}

		assertThat(failures).as("Prompt 用例失败明细").isEmpty();
	}

	private String readResource(String path) throws Exception {
		try (var input = getClass().getClassLoader().getResourceAsStream(path)) {
			assertThat(input).as("找不到测试资源: " + path).isNotNull();
			return new String(input.readAllBytes(), StandardCharsets.UTF_8);
		}
	}

}
