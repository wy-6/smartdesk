package com.smartdesk.ai.provider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.ChatResponsePayload;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.Msg;

import reactor.core.publisher.Flux;

/**
 * 基于 OpenAI 兼容协议的 {@link ChatModel} 实现（Week3 / Day16）。
 *
 * <p><b>这个类的存在意义：把"某家厂商的 HTTP 协议"适配成"Spring AI 的标准接口"。</b>
 *
 * <p>这就是<b>适配器模式</b>：上层 {@code AiService} 只依赖 {@code ChatClient}，
 * 底层可以换 mock、OpenAI、DeepSeek、本地 Ollama —— 换的是 Bean，不是业务代码。
 * 面试里讲"如何做模型无关设计"，指的就是这条边界。
 */
public class OpenAiCompatibleChatModel implements ChatModel {

	private final OpenAiChatTransport transport;

	private final AiProperties properties;

	public OpenAiCompatibleChatModel(OpenAiChatTransport transport, AiProperties properties) {
		this.transport = transport;
		this.properties = properties;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		ChatResponsePayload payload = transport.complete(toMessages(prompt), false);
		String content = extractContent(payload);
		Integer promptTokens = payload.usage() == null ? null : payload.usage().promptTokens();
		Integer completionTokens = payload.usage() == null ? null : payload.usage().completionTokens();

		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
				.id(payload.id())
				.model(payload.model() == null ? properties.getModel() : payload.model())
				.usage(new DefaultUsage(orZero(promptTokens), orZero(completionTokens)))
				.build();
		return ChatResponse.builder()
				.generations(List.of(new Generation(new AssistantMessage(content))))
				.metadata(metadata)
				.build();
	}

	/**
	 * 流式调用。
	 *
	 * <p>上游每个 SSE 分片只带增量文本，所以这里每个 {@code ChatResponse}
	 * 也只带增量。拼接由上层（ChatClient / Controller）负责。
	 *
	 * <p><b>注意：流式响应通常拿不到 token 用量</b>（OpenAI 需要显式开启
	 * {@code stream_options.include_usage}）。所以流式调用日志里的 Token 只能是估算值，
	 * 这一点必须在文档里写清楚，不能假装精确。
	 */
	@Override
	public Flux<ChatResponse> stream(Prompt prompt) {
		return transport.stream(toMessages(prompt))
				.map(delta -> ChatResponse.builder()
						.generations(List.of(new Generation(new AssistantMessage(delta))))
						.build());
	}

	@Override
	public ChatOptions getOptions() {
		return ChatOptions.builder()
				.model(properties.getModel())
				.temperature(properties.getTemperature())
				.build();
	}

	private List<Msg> toMessages(Prompt prompt) {
		List<Msg> messages = new ArrayList<>();
		for (Message message : prompt.getInstructions()) {
			messages.add(new Msg(role(message.getMessageType()), message.getText()));
		}
		return messages;
	}

	private static String role(MessageType type) {
		return switch (type) {
			case SYSTEM -> "system";
			case USER -> "user";
			case ASSISTANT -> "assistant";
			// TOOL 消息第 4 周才会出现，这里先按 user 兜底，避免 NPE
			default -> "user";
		};
	}

	private static String extractContent(ChatResponsePayload payload) {
		if (payload == null || payload.choices() == null || payload.choices().isEmpty()
				|| payload.choices().get(0).message() == null) {
			return "";
		}
		String content = payload.choices().get(0).message().content();
		return content == null ? "" : content;
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}

}
