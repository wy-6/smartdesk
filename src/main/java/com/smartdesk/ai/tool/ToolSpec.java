package com.smartdesk.ai.tool;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 一个可被模型调用的工具的元数据（Week4 / Day23）。
 *
 * <p><b>为什么不直接把 {@code Method} 丢给模型？</b>
 * 模型只认 JSON Schema，不认 Java 反射。把"方法签名"翻译成"模型能读的说明书"，
 * 就是 {@link #parameterSchema()} 存在的唯一理由 —— 这份 schema 是人和模型之间的契约。
 *
 * <p><b>三个字段决定工具好不好用：</b>
 * <ol>
 *   <li><b>name</b>：动宾结构、语义明确（{@code listTasks} 而不是 {@code doIt}）。
 *       模型选哪个工具，主要靠名字</li>
 *   <li><b>description</b>：写清"什么时候该用"比"它能做什么"更重要。
 *       写"查询当前用户的逾期任务，当用户问'有什么要过期了'时使用"，
 *       比写"查询任务列表"的命中率高一个档次</li>
 *   <li><b>parameterSchema</b>：枚举值必须穷举、必填项必须标 required。
 *       模型最常犯的错就是编造一个不存在的状态值</li>
 * </ol>
 *
 * <p><b>{@code sideEffect} 是本项目的安全开关</b>：有副作用的工具（创建、修改、删除）
 * 不直接执行，而是走"生成草稿 → 用户确认 → 才落库"的两阶段流程。
 * 见 {@code ConfirmationService}。
 *
 * @param name 工具名，全局唯一，与模型返回的 {@code tool_calls[].function.name} 对应
 * @param description 给模型看的说明，不是给人看的
 * @param parameterSchema JSON Schema（{@code type=object}）
 * @param sideEffect 是否有副作用；true 表示必须走人工确认
 */
public record ToolSpec(String name, String description, Map<String, Object> parameterSchema, boolean sideEffect) {

	/**
	 * 转成 OpenAI 协议的 tools 数组元素。
	 *
	 * <p>格式：{@code {"type":"function","function":{"name":..,"description":..,"parameters":..}}}
	 *
	 * <p><b>为什么用 {@link LinkedHashMap} 而不是 {@code Map.of}？</b>
	 * 序列化后的字段顺序会体现在发给模型的 JSON 里。保持 name → description → parameters
	 * 的固定顺序，调试时人能一眼看懂；{@code Map.of} 的顺序是不保证的。
	 */
	public Map<String, Object> toOpenAiTool() {
		Map<String, Object> function = new LinkedHashMap<>();
		function.put("name", name);
		function.put("description", description);
		function.put("parameters", parameterSchema);

		Map<String, Object> tool = new LinkedHashMap<>();
		tool.put("type", "function");
		tool.put("function", function);
		return tool;
	}

	/** schema 里声明的参数名，用于审计与越权参数检查。 */
	@SuppressWarnings("unchecked")
	public List<String> parameterNames() {
		Object properties = parameterSchema.get("properties");
		if (!(properties instanceof Map<?, ?> map)) {
			return List.of();
		}
		List<String> names = new ArrayList<>();
		((Map<String, Object>) map).keySet().forEach(names::add);
		return names;
	}

}
