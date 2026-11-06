package com.smartdesk.ai.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.smartdesk.ai.provider.MockChatModel;
import com.smartdesk.ai.provider.OpenAiChatTransport;
import com.smartdesk.ai.provider.OpenAiCompatibleChatModel;

import tools.jackson.databind.ObjectMapper;

/**
 * AI 装配（Week3 / Day16）。
 *
 * <p><b>本类是全项目"可替换性"的关键点：</b>它决定注入哪个 {@link ChatModel}。
 * 换模型不需要改一行业务代码，因为业务代码只依赖 {@link ChatClient}。
 *
 * <p><b>为什么选了 openai 但没给 Key 就直接启动失败？</b>
 * 这是第 2 周定下的 fail-fast 约定：配置错误要在启动时暴露，
 * 而不是等第一个用户提问时返回 500 —— 后者在监控上表现为"业务故障"，
 * 排查成本高一个数量级。
 */
@Configuration
@EnableConfigurationProperties(AiProperties.class)
public class AiConfig {

	private static final Logger log = LoggerFactory.getLogger(AiConfig.class);

	@Bean
	public ChatModel chatModel(AiProperties properties, ObjectMapper objectMapper) {
		String provider = properties.getProvider() == null ? "" : properties.getProvider().toLowerCase();
		return switch (provider) {
			case "mock" -> {
				log.info("AI provider=mock：离线规则引擎，不产生真实模型调用（测试与本地开发用）");
				yield new MockChatModel(properties, objectMapper);
			}
			case "openai" -> {
				String apiKey = properties.getOpenai().getApiKey();
				if (apiKey == null || apiKey.isBlank()) {
					throw new IllegalStateException(
							"AI_PROVIDER=openai 但 OPENAI_API_KEY 为空，拒绝启动。"
									+ "请注入环境变量，或把 AI_PROVIDER 设为 mock。");
				}
				log.info("AI provider=openai baseUrl={} model={}", properties.getOpenai().getBaseUrl(),
						properties.getModel());
				yield new OpenAiCompatibleChatModel(new OpenAiChatTransport(properties, objectMapper), properties);
			}
			default -> throw new IllegalStateException(
					"不支持的 AI provider: " + properties.getProvider() + "，可选值：mock / openai");
		};
	}

	/**
	 * ChatClient 是 Spring AI 的"门面"：统一的 Prompt 构建、调用、流式、结构化输出、
	 * Advisor 链（日志、记忆、RAG 检索都在 Advisor 里扩展）。
	 *
	 * <p>第 5 周的 RAG 就是往这里挂一个 {@code QuestionAnswerAdvisor}，
	 * 第 6 周的会话记忆是挂 {@code MessageChatMemoryAdvisor} ——
	 * 所以现在这层抽象不是过度设计，是为后面两周留的接口。
	 */
	@Bean
	public ChatClient chatClient(ChatModel chatModel) {
		return ChatClient.builder(chatModel).build();
	}

}
