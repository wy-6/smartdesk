package com.smartdesk.ai.provider;

import java.util.ArrayList;
import java.util.List;

import org.springframework.ai.chat.messages.AssistantMessage;
import org.springframework.ai.chat.messages.AssistantMessage.ToolCall;
import org.springframework.ai.chat.messages.Message;
import org.springframework.ai.chat.messages.MessageType;
import org.springframework.ai.chat.messages.ToolResponseMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.DefaultUsage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.model.Generation;
import org.springframework.ai.chat.prompt.ChatOptions;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.ChatResponsePayload;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.Msg;
import com.smartdesk.ai.provider.dto.OpenAiProtocol.ToolCallPayload;
import com.smartdesk.ai.tool.ToolSpec;

import reactor.core.publisher.Flux;

/**
 * 基于 OpenAI 兼容协议的 {@link org.springframework.ai.chat.model.ChatModel} 实现（Week3 / Day16，Week4 扩展）。
 *
 * <p><b>这个类的存在意义：把"某家厂商的 HTTP 协议"适配成"Spring AI 的标准接口"。</b>
 *
 * <p>这就是<b>适配器模式</b>：上层 {@code AiService} 只依赖 {@code ChatClient}，
 * 底层可以换 mock、OpenAI、DeepSeek、本地 Ollama —— 换的是 Bean，不是业务代码。
 * 面试里讲"如何做模型无关设计"，指的就是这条边界。
 *
 * <p><b>Week4 新增：工具调用的协议适配</b>。这里只做三件事，<b>不执行工具</b>：
 * <ol>
 *   <li>把 {@link ToolSpec} 序列化成 {@code tools} 数组放进请求体</li>
 *   <li>把响应里的 {@code tool_calls} 解析成 Spring AI 的 {@link ToolCall}</li>
 *   <li>把对话历史里的 {@link ToolResponseMessage} 翻译成 {@code role=tool} 的消息</li>
 * </ol>
 * 执行循环在 {@code AgentLoop} 里 —— 协议归协议，策略归策略。
 */
public class OpenAiCompatibleChatModel implements ToolAwareChatModel {

	private final OpenAiChatTransport transport;

	private final AiProperties properties;

	public OpenAiCompatibleChatModel(OpenAiChatTransport transport, AiProperties properties) {
		this.transport = transport;
		this.properties = properties;
	}

	@Override
	public ChatResponse call(Prompt prompt) {
		return call(prompt, List.of());
	}

	@Override
	public ChatResponse call(Prompt prompt, List<ToolSpec> tools) {
		List<java.util.Map<String, Object>> openAiTools = tools == null ? List.of()
				: tools.stream().map(ToolSpec::toOpenAiTool).toList();
		ChatResponsePayload payload = transport.complete(toMessages(prompt), false, openAiTools);

		String content = extractContent(payload);
		List<ToolCall> toolCalls = extractToolCalls(payload);
		Integer promptTokens = payload.usage() == null ? null : payload.usage().promptTokens();
		Integer completionTokens = payload.usage() == null ? null : payload.usage().completionTokens();

		ChatResponseMetadata metadata = ChatResponseMetadata.builder()
				.id(payload.id())
				.model(payload.model() == null ? properties.getModel() : payload.model())
				.usage(new DefaultUsage(orZero(promptTokens), orZero(completionTokens)))
				.build();
		return ChatResponse.builder()
				.generations(List.of(new Generation(assistantMessage(content, toolCalls))))
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
	 *
	 * <p><b>Week4 的取舍：流式接口不带工具。</b>
	 * 工具调用是"模型说完 → 执行 → 再说"的多段过程，混进单条 SSE 流会变成
	 * 一套自定义事件协议（tool_start / tool_result / delta / done），
	 * 收益不大而复杂度翻倍。需要工具时走同步接口，需要"打字机效果"时走流式接口，
	 * 两者用不同的入口，这是很多生产系统的实际做法。
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

	private static AssistantMessage assistantMessage(String content, List<ToolCall> toolCalls) {
		if (toolCalls == null || toolCalls.isEmpty()) {
			return new AssistantMessage(content == null ? "" : content);
		}
		// 带 tool_calls 的消息 content 可能为 null（模型只调工具不说话）
		return AssistantMessage.builder()
				.content(content)
				.toolCalls(toolCalls)
				.build();
	}

	/**
	 * 把 Spring AI 的消息翻译成协议消息。
	 *
	 * <p><b>两个分支必须精确，错一点模型就会"答非所问"：</b>
	 * <ul>
	 *   <li>{@link ToolResponseMessage} → 一条 {@code role=tool} 消息，
	 *       带 {@code tool_call_id}；一次工具调用一条，不能合并</li>
	 *   <li>带 toolCalls 的 {@link AssistantMessage} → {@code role=assistant}
	 *       且带 {@code tool_calls}。<b>这条消息必须原样回填</b>，
	 *       否则上游会报 "tool message without preceding tool_calls"</li>
	 * </ul>
	 */
	private List<Msg> toMessages(Prompt prompt) {
		List<Msg> messages = new ArrayList<>();
		for (Message message : prompt.getInstructions()) {
			if (message instanceof ToolResponseMessage toolResponse) {
				for (ToolResponseMessage.ToolResponse response : toolResponse.getResponses()) {
					messages.add(Msg.tool(response.id(), response.responseData()));
				}
				continue;
			}
			if (message instanceof AssistantMessage assistant && assistant.hasToolCalls()) {
				List<ToolCallPayload> calls = assistant.getToolCalls()
						.stream()
						.map(call -> ToolCallPayload.function(call.id(), call.name(), call.arguments()))
						.toList();
				messages.add(Msg.assistantToolCalls(calls));
				continue;
			}
			messages.add(new Msg(role(message.getMessageType()), message.getText()));
		}
		return messages;
	}

	private static String role(MessageType type) {
		return switch (type) {
			case SYSTEM -> "system";
			case USER -> "user";
			case ASSISTANT -> "assistant";
			case TOOL -> "tool";
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

	/**
	 * 解析 {@code tool_calls}。
	 *
	 * <p>三个防御点，都是接真实模型时踩出来的：
	 * <ol>
	 *   <li>{@code id} 可能为空（个别兼容服务不返回）→ 自己造一个，
	 *       否则回填 tool 消息时无法配对</li>
	 *   <li>{@code arguments} 可能为空字符串（无参工具）→ 补 {@code "{}"}，
	 *       下游 JSON 解析才不会炸</li>
	 *   <li>{@code function} 可能为 null（响应格式异常）→ 跳过，不要让整个请求失败</li>
	 * </ol>
	 */
	private static List<ToolCall> extractToolCalls(ChatResponsePayload payload) {
		if (payload == null || payload.choices() == null || payload.choices().isEmpty()) {
			return List.of();
		}
		var message = payload.choices().get(0).message();
		if (message == null || message.toolCalls() == null || message.toolCalls().isEmpty()) {
			return List.of();
		}
		List<ToolCall> calls = new ArrayList<>(message.toolCalls().size());
		for (ToolCallPayload call : message.toolCalls()) {
			if (call == null || call.function() == null || call.function().name() == null) {
				continue;
			}
			String id = call.id() == null || call.id().isBlank() ? "call_" + calls.size() : call.id();
			String arguments = call.function().arguments() == null || call.function().arguments().isBlank() ? "{}"
					: call.function().arguments();
			calls.add(new ToolCall(id, call.type() == null ? "function" : call.type(), call.function().name(),
					arguments));
		}
		return calls;
	}

	private static int orZero(Integer value) {
		return value == null ? 0 : value;
	}

}
