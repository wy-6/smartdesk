package com.smartdesk.ai.provider.dto;

import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * OpenAI 兼容协议的报文结构（Week3 / Day15）。
 *
 * <p><b>为什么用 record 而不是 Map？</b>
 * 用 Map 拼请求体，"字段名写错"要到运行时才炸；record 让字段名编译期就固定，
 * 也能被 IDE 跳转。序列化 / 反序列化交给 Jackson，无需手写。
 *
 * <p><b>为什么都加 {@code @JsonIgnoreProperties(ignoreUnknown = true)}？</b>
 * 各家兼容服务都会<b>偷偷多返回字段</b>（reasoning_content、system_fingerprint、
 * prompt_cache_hit_tokens……）。严格模式会直接反序列化失败 —— 这是接第三方 API
 * 最常见的坑之一。宁可忽略未知字段，也不要因为对方升级而挂掉。
 *
 * <p><b>注意注解包名：</b>Jackson 3 的 databind 搬到了 {@code tools.jackson}，
 * 但注解仍在 {@code com.fasterxml.jackson.annotation}，不要改。
 */
public final class OpenAiProtocol {

	private OpenAiProtocol() {
	}

	/**
	 * 一条对话消息。
	 *
	 * <p><b>第 4 周新增两个字段，它们构成工具调用的"协议往返"：</b>
	 * <ol>
	 *   <li>assistant 消息带 {@code tool_calls}：模型说"我要调这些工具"</li>
	 *   <li>tool 消息带 {@code tool_call_id}：工具结果必须<b>精确对应</b>某一次调用</li>
	 * </ol>
	 *
	 * <p><b>为什么 {@code tool_call_id} 不能省？</b>
	 * 一轮可能有多个工具调用（并行调用），结果回来的顺序不保证。
	 * 没有 id 就无法把"结果"配对到"请求"，模型会张冠李戴。
	 * 这也是真实接入时最容易漏的字段 —— 漏了之后模型的表现是"答非所问"，
	 * 很难从错误信息看出来。
	 *
	 * <p>保留 {@code Msg(role, content)} 两参构造器，是为了让第 3 周的代码
	 * （system/user 消息）一行都不用改。
	 */
	public record Msg(String role, String content,
			@JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("tool_calls") List<ToolCallPayload> toolCalls,
			@JsonInclude(JsonInclude.Include.NON_NULL) @JsonProperty("tool_call_id") String toolCallId) {

		public Msg(String role, String content) {
			this(role, content, null, null);
		}

		public static Msg system(String content) {
			return new Msg("system", content);
		}

		public static Msg user(String content) {
			return new Msg("user", content);
		}

		public static Msg assistant(String content) {
			return new Msg("assistant", content);
		}

		/** 模型发起工具调用的那条 assistant 消息。 */
		public static Msg assistantToolCalls(List<ToolCallPayload> toolCalls) {
			return new Msg("assistant", null, toolCalls, null);
		}

		/** 工具执行结果。{@code toolCallId} 必须与请求时的 id 完全一致。 */
		public static Msg tool(String toolCallId, String content) {
			return new Msg("tool", content, null, toolCallId);
		}

	}

	/** 模型请求调用一个工具。{@code arguments} 是 <b>JSON 字符串</b>，不是对象。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ToolCallPayload(String id, String type, FunctionCall function) {

		public static ToolCallPayload function(String id, String name, String arguments) {
			return new ToolCallPayload(id, "function", new FunctionCall(name, arguments));
		}

	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record FunctionCall(String name, String arguments) {
	}

	/** 是否要求上游返回 JSON 对象。并非所有兼容服务都支持。 */
	public record ResponseFormat(String type) {

		public static ResponseFormat jsonObject() {
			return new ResponseFormat("json_object");
		}

	}

	/** 请求体。null 字段不序列化，避免给不支持的服务传多余参数。 */
	@JsonInclude(JsonInclude.Include.NON_NULL)
	public record ChatRequest(String model, List<Msg> messages, Double temperature, Boolean stream,
			@JsonProperty("response_format") ResponseFormat responseFormat,
			/*
			 * tools 的格式：{"type":"function","function":{"name":..,"description":..,"parameters":..}}
			 * 用 Map 而不是强类型 record：不同厂商对 tools 的扩展字段（strict、cache_control）
			 * 差异很大，强类型反而会把不认识的字段丢掉
			 */
			List<Map<String, Object>> tools,
			@JsonProperty("tool_choice") String toolChoice) {

		/** 第 3 周的无工具调用路径，保持不变。 */
		public ChatRequest(String model, List<Msg> messages, Double temperature, Boolean stream,
				ResponseFormat responseFormat) {
			this(model, messages, temperature, stream, responseFormat, null, null);
		}

	}

	/** 非流式响应。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatResponsePayload(String id, String model, List<Choice> choices, Usage usage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(String role, String content,
			@JsonProperty("tool_calls") List<ToolCallPayload> toolCalls) {
	}

	/** Token 用量。上游可能不返回，所以全部用包装类型。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Usage(@JsonProperty("prompt_tokens") Integer promptTokens,
			@JsonProperty("completion_tokens") Integer completionTokens,
			@JsonProperty("total_tokens") Integer totalTokens) {
	}

	/** 流式分片。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StreamChunk(List<StreamChoice> choices, Usage usage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record StreamChoice(Delta delta, @JsonProperty("finish_reason") String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Delta(String content) {
	}

	/** 错误响应体。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ErrorResponse(ErrorBody error) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ErrorBody(String message, String type, String code) {
	}

}
