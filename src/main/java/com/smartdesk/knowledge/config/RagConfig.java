package com.smartdesk.knowledge.config;

import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.knowledge.embedding.MockEmbeddingModel;
import com.smartdesk.knowledge.embedding.OpenAiCompatibleEmbeddingModel;
import com.smartdesk.knowledge.service.DocumentTextExtractor;

import tools.jackson.databind.ObjectMapper;

/**
 * RAG 模块配置（Week5 / Day31）。
 *
 * <p><b>三个 Bean：</b>
 * <ul>
 *   <li>{@link EmbeddingModel}：按配置选 mock 或 OpenAI 兼容实现。
 *       业务代码只依赖 Spring AI 的 {@code EmbeddingModel} 接口</li>
 *   <li>{@link DocumentTextExtractor}：文档解析（无状态，简单 new 即可）</li>
 *   <li>配置校验：{@code chunk-overlap >= chunk-size} 这种错误必须在启动时暴露，
 *       不能等到用户导入文档时才发现切片死循环</li>
 * </ul>
 *
 * <p><b>为什么 Embedding 的 provider 和 Chat 的 provider 是两套配置？</b>
 * 因为它们是两回事：聊天模型生成文本，嵌入模型把文本变成向量。
 * 而且现实情况经常是"聊天用 A 家的、嵌入用 B 家的" ——
 * DeepSeek 有聊天没有嵌入，百炼两家都有。
 */
@Configuration
@EnableConfigurationProperties(RagProperties.class)
public class RagConfig {

	@Bean
	public EmbeddingModel embeddingModel(RagProperties properties, AiProperties aiProperties,
			ObjectMapper objectMapper) {
		if (properties.getEmbedding().useOpenAi()) {
			if (aiProperties.getOpenai().getApiKey() == null || aiProperties.getOpenai().getApiKey().isBlank()) {
				// 与 Chat 的 fail-fast 一个道理：配了就必须能用，不能静默降级到 mock
				// （静默降级更可怕 —— 用户以为在用真嵌入，其实检索质量天差地别）
				throw new IllegalStateException(
						"RAG_EMBEDDING_PROVIDER=openai 但 OPENAI_API_KEY 为空，拒绝启动（或改回 mock）");
			}
			return new OpenAiCompatibleEmbeddingModel(aiProperties, properties.getEmbedding().getModel(),
					objectMapper);
		}
		return new MockEmbeddingModel(properties.getEmbedding().getDimensions());
	}

	@Bean
	public DocumentTextExtractor documentTextExtractor() {
		return new DocumentTextExtractor();
	}

	@Bean
	public RagPropertiesValidator ragPropertiesValidator(RagProperties properties) {
		return new RagPropertiesValidator(properties);
	}

}
