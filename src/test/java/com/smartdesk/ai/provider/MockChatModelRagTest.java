package com.smartdesk.ai.provider;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.knowledge.prompt.RagPromptTemplates;

/**
 * Mock 的 RAG 渲染测试（Week5 补充）。
 *
 * <p><b>为什么单独写一个类？</b>
 * mock 的 RAG 分支曾经有个真实缺陷：一个切片里含多个小节时，
 * 它永远取第一片的<b>第一句</b>作答 —— 问"住宿费上限"会答成"年假制度"。
 * 这类"答非所问"的 bug 不会被"回答里带 [1]"这类断言发现，
 * 必须有一条专门断言"回答内容与问题相关"的测试才守得住。
 */
class MockChatModelRagTest {

	private final MockChatModel model = new MockChatModel(properties(), new tools.jackson.databind.ObjectMapper());

	private static AiProperties properties() {
		AiProperties properties = new AiProperties();
		properties.getMock().setLatencyMillis(0);
		properties.setMockChunkDelayMillis(0);
		return properties;
	}

	/** 按 RagPromptTemplates 的格式拼一条资料区块（与真实调用完全一致）。 */
	private static String material(int index, String source, String content) {
		return "[" + index + "]（来源：" + source + "，片段 #" + (index - 1) + "）\n" + content + "\n\n";
	}

	private String ask(String question, String... materials) {
		StringBuilder user = new StringBuilder(RagPromptTemplates.MATERIAL_HEADER).append('\n');
		for (String m : materials) {
			user.append(m);
		}
		user.append("问题：").append(question);
		Prompt prompt = new Prompt(List.of(
				new SystemMessage(RagPromptTemplates.ragSystem()),
				new UserMessage(user.toString())));
		ChatResponse response = model.call(prompt);
		return response.getResult().getOutput().getText();
	}

	@Test
	@DisplayName("RAG：回答取资料里与问题最相关的那一句，而不是第一句")
	void picksMostRelevantSentenceInsideMaterial() {
		String content = """
				一、年假制度
				员工入职满一年可享受5天带薪年假。年假需提前3个工作日在OA系统申请。

				二、报销制度
				差旅报销需在行程结束后10个工作日内提交。住宿费凭发票据实报销，每人每晚上限600元。""";
		String answer = ask("住宿费每人每晚上限多少？", material(1, "员工手册", content));

		assertThat(answer)
				.as("应命中报销制度那句，而不是第一句「一、年假制度」")
				.contains("住宿费")
				.doesNotContain("年假制度");
	}

	@Test
	@DisplayName("RAG：引用编号固定指向分数最高的那条资料")
	void citationPointsToTopScoredMaterial() {
		String content = "住宿费凭发票据实报销，每人每晚上限600元。";
		String answer = ask("住宿费上限多少？", material(1, "员工手册", content));

		assertThat(answer).contains("[1]");
		assertThat(answer).contains("来源：员工手册");
	}

	@Test
	@DisplayName("RAG：问题与资料毫无词元交集时退回第一句，不返回 null")
	void fallsBackToFirstSentenceWhenNoOverlap() {
		String content = "一、年假制度\n员工入职满一年可享受5天带薪年假。";
		String answer = ask("量子纠缠的贝尔不等式如何验证？", material(1, "员工手册", content));

		assertThat(answer).contains("年假制度");
		assertThat(answer).contains("[1]");
	}

	@Test
	@DisplayName("RAG：没有资料时返回拒答话术")
	void refusesWithoutMaterials() {
		String answer = ask("住宿费上限多少？");

		assertThat(answer).contains("无法确认");
		assertThat(answer).doesNotContain("[1]");
	}

	@Test
	@DisplayName("RAG：回答永远带引用编号，不会出现凭空引用")
	void alwaysCarriesCitationMarker() {
		String content = "住宿费凭发票据实报销，每人每晚上限600元。市内交通费每次报销上限50元。";
		String answer = ask("市内交通费上限多少？", material(1, "员工手册", content));

		assertThat(answer).containsPattern("\\[\\d+]");
	}
}
