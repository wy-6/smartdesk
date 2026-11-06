package com.smartdesk.ai.provider.dto;

import java.util.List;

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

	/** 一条对话消息。 */
	public record Msg(String role, String content) {

		public static Msg system(String content) {
			return new Msg("system", content);
		}

		public static Msg user(String content) {
			return new Msg("user", content);
		}

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
			@JsonProperty("response_format") ResponseFormat responseFormat) {
	}

	/** 非流式响应。 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record ChatResponsePayload(String id, String model, List<Choice> choices, Usage usage) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Choice(Message message, @JsonProperty("finish_reason") String finishReason) {
	}

	@JsonIgnoreProperties(ignoreUnknown = true)
	public record Message(String role, String content) {
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
