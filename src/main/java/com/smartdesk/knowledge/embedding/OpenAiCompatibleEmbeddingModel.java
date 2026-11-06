package com.smartdesk.knowledge.embedding;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

import com.smartdesk.ai.config.AiProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容协议的 Embedding 模型（Week5 / Day31）。
 *
 * <p>调用 {@code POST {base-url}/embeddings}，请求体
 * {@code {"model":"...","input":["文本1","文本2"]}}，
 * 响应 {@code {"data":[{"index":0,"embedding":[0.1,0.2,...]}]}}。
 *
 * <p><b>可用的服务：</b>阿里云百炼（{@code text-embedding-v3}，1024/2048 维可选）、
 * OpenAI 官方（{@code text-embedding-3-small}）、
 * 本地部署的 Ollama / vLLM（{@code /v1/embeddings}）。
 *
 * <p><b>重要提醒：DeepSeek 没有 embedding 接口。</b>
 * 如果聊天用 DeepSeek、嵌入想用百炼，是两把 Key、两个 base-url。
 * 本项目的配置把它们拆开了：聊天看 {@code smartdesk.ai.openai}，
 * 嵌入看 {@code smartdesk.rag.embedding}。
 *
 * <p><b>Embedding 和 Chat 是两种模型、两种计费</b>：
 * embedding 模型没有"生成"能力，只做文本→向量；
 * 而且很多服务商对 embedding 单独计费（比 chat 便宜，但按 token 量算）。
 */
public class OpenAiCompatibleEmbeddingModel implements EmbeddingModel {

	private final AiProperties aiProperties;

	private final String embeddingModel;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	public OpenAiCompatibleEmbeddingModel(AiProperties aiProperties, String embeddingModel, ObjectMapper objectMapper) {
		this.aiProperties = aiProperties;
		this.embeddingModel = embeddingModel;
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<String> texts = request.getInstructions();
		if (texts.isEmpty()) {
			return new EmbeddingResponse(List.of());
		}
		Map<String, Object> body = Map.of("model", embeddingModel, "input", texts);
		String responseText = post(body);
		List<Embedding> results = parse(responseText);
		return new EmbeddingResponse(results);
	}

	@Override
	public float[] embed(Document document) {
		List<Embedding> results = call(new EmbeddingRequest(List.of(document.getText()), null)).getResults();
		return results.isEmpty() ? new float[0] : results.get(0).getOutput();
	}

	@Override
	public int dimensions() {
		// 真实模型的维度由服务商决定；这里返回 0 表示"未知"，
		// 调用方不应该依赖这个值做校验 —— 维度以库里存的 embedding_dim 为准。
		return 0;
	}

	@SuppressWarnings("unchecked")
	private List<Embedding> parse(String body) {
		try {
			Map<String, Object> root = objectMapper.readValue(body, Map.class);
			Object data = root.get("data");
			if (!(data instanceof List<?> list)) {
				throw new IllegalStateException("响应缺少 data 数组");
			}
			List<Embedding> results = new ArrayList<>();
			for (Object item : list) {
				Map<String, Object> entry = (Map<String, Object>) item;
				Number index = (Number) entry.get("index");
				Object vector = entry.get("embedding");
				if (!(vector instanceof List<?> values)) {
					throw new IllegalStateException("响应缺少 embedding 数组");
				}
				float[] output = new float[values.size()];
				for (int i = 0; i < values.size(); i++) {
					output[i] = ((Number) values.get(i)).floatValue();
				}
				results.add(new Embedding(output, index == null ? results.size() : index.intValue()));
			}
			return results;
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Embedding 响应无法解析：" + ex.getMessage(), ex);
		}
	}

	private String post(Map<String, Object> body) {
		String json;
		try {
			json = objectMapper.writeValueAsString(body);
		}
		catch (RuntimeException ex) {
			throw new IllegalStateException("Embedding 请求序列化失败", ex);
		}
		HttpRequest request = HttpRequest.newBuilder()
				.uri(URI.create(aiProperties.getOpenai().getBaseUrl() + "/embeddings"))
				.timeout(Duration.ofSeconds(aiProperties.getRequestTimeout().toSeconds()))
				.header("Content-Type", "application/json")
				.header("Authorization", "Bearer " + aiProperties.getOpenai().getApiKey())
				.POST(HttpRequest.BodyPublishers.ofString(json, StandardCharsets.UTF_8))
				.build();
		HttpResponse<String> response;
		try {
			response = httpClient.send(request, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (HttpTimeoutException ex) {
			throw new IllegalStateException("Embedding 服务响应超时", ex);
		}
		catch (IOException ex) {
			throw new IllegalStateException("无法连接 Embedding 服务（该服务商可能不提供 embedding 接口）", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("Embedding 调用被中断", ex);
		}
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			// 只回传状态码，不回传响应体：响应体可能含账号信息
			throw new IllegalStateException("Embedding 服务返回错误状态：" + response.statusCode());
		}
		return response.body();
	}

}
