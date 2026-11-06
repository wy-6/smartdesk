package com.smartdesk.ai.provider;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.ChatRequest;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.ChatResponsePayload;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.Msg;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.ResponseFormat;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.StreamChunk;

import reactor.core.publisher.Flux;
import reactor.core.publisher.FluxSink;
import tools.jackson.databind.ObjectMapper;

/**
 * OpenAI 兼容协议的 HTTP 传输层（Week3 / Day15）。
 *
 * <p><b>这就是"用 Java HTTP 客户端完成一次调用"的落地形态</b>，
 * 也是整周唯一直接说 HTTP 的地方。上面所有业务代码只认 Spring AI 的
 * {@code ChatClient} / {@code ChatModel}，不知道这里用的是 JDK HttpClient 还是别的。
 *
 * <p><b>为什么用 {@code java.net.http.HttpClient} 而不是 RestTemplate / WebClient？</b>
 * <ul>
 *   <li>JDK 自带，零依赖，支持同步 + 流式（{@code BodyHandlers.ofInputStream}）</li>
 *   <li>RestTemplate 已进入维护模式；WebClient 需要 WebFlux 栈</li>
 *   <li>需要精细控制超时（连接超时 vs 整请求超时），HttpClient 两种都给了</li>
 * </ul>
 *
 * <p><b>面试可讲：为什么超时要设两层？</b>
 * 只设总超时，DNS 卡住也会占满线程；只设连接超时，服务端"接住了但永远不回"
 * 同样能耗死连接池。所以连接 5s + 整请求 20s。
 */
public class OpenAiChatTransport {

	private static final Logger log = LoggerFactory.getLogger(OpenAiChatTransport.class);

	private static final String DATA_PREFIX = "data:";

	private static final String DONE = "[DONE]";

	private final AiProperties properties;

	private final ObjectMapper objectMapper;

	private final HttpClient httpClient;

	public OpenAiChatTransport(AiProperties properties, ObjectMapper objectMapper) {
		this.properties = properties;
		this.objectMapper = objectMapper;
		this.httpClient = HttpClient.newBuilder()
				// 连接超时：TCP 握手阶段
				.connectTimeout(Duration.ofSeconds(5))
				.build();
	}

	/** 同步调用。 */
	public ChatResponsePayload complete(List<Msg> messages, boolean jsonMode) {
		return complete(messages, jsonMode, List.of());
	}

	/**
	 * 带工具定义的同步调用（Week4）。
	 *
	 * <p>{@code tool_choice="auto"} 表示"模型自己决定调不调工具"。
	 * 其他常见取值：{@code "none"}（禁止调用，用于强制纯文本回答）、
	 * {@code {"type":"function","function":{"name":"x"}}（强制调某个工具）。
	 * 本项目用 auto —— 大部分请求都不需要工具，强制调用会浪费时间与 token。
	 *
	 * @param tools OpenAI 协议的 tools 数组，空列表表示不带工具
	 */
	public ChatResponsePayload complete(List<Msg> messages, boolean jsonMode, List<Map<String, Object>> tools) {
		boolean withTools = tools != null && !tools.isEmpty();
		ChatRequest payload = new ChatRequest(properties.getModel(), messages, properties.getTemperature(), false,
				jsonMode ? ResponseFormat.jsonObject() : null, withTools ? tools : null,
				withTools ? "auto" : null);

		HttpResponse<String> response;
		try {
			response = httpClient.send(buildRequest(payload), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
		}
		catch (HttpTimeoutException ex) {
			throw new AiException(AiErrorCode.TIMEOUT, "模型服务响应超时（" + properties.getRequestTimeout() + "）", ex);
		}
		catch (IOException ex) {
			throw new AiException(AiErrorCode.UPSTREAM_ERROR, "无法连接模型服务", ex);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException(AiErrorCode.UPSTREAM_ERROR, "模型调用被中断", ex);
		}

		String body = response.body();
		if (response.statusCode() < 200 || response.statusCode() >= 300) {
			throw mapHttpError(response.statusCode(), body);
		}
		try {
			return objectMapper.readValue(body, ChatResponsePayload.class);
		}
		catch (RuntimeException ex) {
			log.error("模型响应无法解析，前 200 字符={}", abbreviate(body));
			throw new AiException(AiErrorCode.UPSTREAM_ERROR, "模型响应格式不符合 OpenAI 协议", ex);
		}
	}

	/**
	 * 流式调用。
	 *
	 * <p>返回 {@code Flux<String>}，每个元素是一个增量片段（delta）。
	 * <b>注意：这个 Flux 是冷流</b>，只有被订阅才开始发 HTTP 请求；
	 * 阻塞读必须放到 boundedElastic 线程池，否则会占满 Tomcat 工作线程。
	 */
	public Flux<String> stream(List<Msg> messages) {
		ChatRequest payload = new ChatRequest(properties.getModel(), messages, properties.getTemperature(), true,
				null);

		return Flux.create(sink -> {
			HttpResponse<InputStream> response;
			try {
				response = httpClient.send(buildRequest(payload), HttpResponse.BodyHandlers.ofInputStream());
			}
			catch (HttpTimeoutException ex) {
				sink.error(new AiException(AiErrorCode.TIMEOUT, "模型服务响应超时（流式）", ex));
				return;
			}
			catch (IOException ex) {
				sink.error(new AiException(AiErrorCode.UPSTREAM_ERROR, "无法连接模型服务（流式）", ex));
				return;
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
				sink.error(new AiException(AiErrorCode.UPSTREAM_ERROR, "模型调用被中断", ex));
				return;
			}

			if (response.statusCode() < 200 || response.statusCode() >= 300) {
				sink.error(mapHttpError(response.statusCode(), readQuietly(response.body())));
				return;
			}

			try (BufferedReader reader = new BufferedReader(
					new InputStreamReader(response.body(), StandardCharsets.UTF_8))) {
				String line;
				while ((line = reader.readLine()) != null) {
					if (sink.isCancelled()) {
						log.debug("客户端已断开，停止读取模型流");
						return;
					}
					if (!line.startsWith(DATA_PREFIX)) {
						// 空行、注释行（: keep-alive）直接跳过
						continue;
					}
					String data = line.substring(DATA_PREFIX.length()).strip();
					if (DONE.equals(data)) {
						break;
					}
					String delta = parseDelta(data);
					if (delta != null && !delta.isEmpty()) {
						sink.next(delta);
					}
				}
				sink.complete();
			}
			catch (IOException ex) {
				sink.error(new AiException(AiErrorCode.UPSTREAM_ERROR, "读取模型流失败", ex));
			}
		});
	}

	/**
	 * 把上游 HTTP 错误翻译成本项目的错误码。
	 *
	 * <p>注意这里只透出上游 message，不打印请求头 —— 请求头里有 API Key。
	 */
	private AiException mapHttpError(int status, String body) {
		String upstream = extractErrorMessage(body);
		if (status == 429) {
			return new AiException(AiErrorCode.RATE_LIMITED, "模型服务限流：" + upstream);
		}
		if (status >= 500) {
			return new AiException(AiErrorCode.UPSTREAM_ERROR, "模型服务内部错误（" + status + "）：" + upstream);
		}
		if (status == 401 || status == 403) {
			// 不能把"Key 无效"直接告诉终端用户，但日志里要能定位
			log.error("模型服务鉴权失败 status={} message={}", status, upstream);
			return new AiException(AiErrorCode.NOT_CONFIGURED, "模型服务鉴权失败，请检查 API Key 配置");
		}
		return new AiException(AiErrorCode.INVALID_REQUEST, "模型请求被拒绝（" + status + "）：" + upstream);
	}

	private String extractErrorMessage(String body) {
		try {
			var error = objectMapper.readValue(body, com.smartdesk.ai.provider.dto.OpenAiProtocol.ErrorResponse.class);
			if (error != null && error.error() != null && error.error().message() != null) {
				return abbreviate(error.error().message());
			}
		}
		catch (RuntimeException ignored) {
			// 错误响应本身也可能不是 JSON（网关的 HTML 错误页），忽略即可
		}
		return abbreviate(body);
	}

	private String parseDelta(String data) {
		try {
			StreamChunk chunk = objectMapper.readValue(data, StreamChunk.class);
			if (chunk.choices() == null || chunk.choices().isEmpty()) {
				return null;
			}
			var delta = chunk.choices().get(0).delta();
			return delta == null ? null : delta.content();
		}
		catch (RuntimeException ex) {
			log.warn("无法解析流式分片，已跳过：{}", abbreviate(data));
			return null;
		}
	}

	private HttpRequest buildRequest(ChatRequest payload) {
		String baseUrl = properties.getOpenai().getBaseUrl().replaceAll("/+$", "");
		String body = objectMapper.writeValueAsString(payload);
		return HttpRequest.newBuilder(URI.create(baseUrl + "/chat/completions"))
				.timeout(properties.getRequestTimeout())
				.header("Content-Type", "application/json; charset=UTF-8")
				.header("Authorization", "Bearer " + properties.getOpenai().getApiKey())
				.POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8))
				.build();
	}

	private static String readQuietly(InputStream inputStream) {
		try (InputStream in = inputStream) {
			return new String(in.readAllBytes(), StandardCharsets.UTF_8);
		}
		catch (IOException ex) {
			return "";
		}
	}

	private static String abbreviate(String text) {
		if (text == null) {
			return "";
		}
		String singleLine = text.replaceAll("\\s+", " ").strip();
		return singleLine.length() > 300 ? singleLine.substring(0, 300) + "…" : singleLine;
	}

}
