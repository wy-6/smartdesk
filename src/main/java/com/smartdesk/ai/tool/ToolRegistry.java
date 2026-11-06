package com.smartdesk.ai.tool;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;
import org.springframework.stereotype.Component;

import tools.jackson.databind.ObjectMapper;

/**
 * 工具注册表（Week4 / Day23-24）。
 *
 * <p><b>它做三件事，每一件都有明确的失败模式：</b>
 * <ol>
 *   <li><b>扫描</b>：把 {@link ToolProvider} 里标了 {@code @Tool} 的方法变成 {@link ToolSpec}。
 *       失败模式：工具名重复 → 启动直接报错（静默覆盖会让"调错工具"成为悬案）</li>
 *   <li><b>翻译</b>：把 Java 方法签名翻译成 JSON Schema 给模型看。
 *       失败模式：参数名丢失（编译没加 {@code -parameters}）→ 模型看到 {@code arg0}，
 *       只能瞎猜。所以注册时就检查 {@code isNamePresent()}，不给它运行时才炸的机会</li>
 *   <li><b>执行</b>：解析模型给的 JSON 参数、注入上下文、反射调用、把异常转成可读文案。
 *       失败模式：<b>任何异常都不向外抛</b>，统一转成 {@link ToolExecutionResult}
 *       让模型自己纠正 —— 见该类注释</li>
 * </ol>
 *
 * <p><b>为什么不用 Spring AI 自带的 {@code MethodToolCallbackProvider}？</b>
 * 它确实能做 1 和 2，但第 3 步的执行循环在 {@code DefaultToolCallingManager} 里，
 * 轮数上限、单轮工具数、超时预算、重复调用熔断这些策略都插不进去。
 * 本项目 Day26 的验收点就是这些策略，所以循环必须自己写，
 * 而自己写循环的前提是"工具执行"这一层也自己掌握。
 */
@Component
public class ToolRegistry {

	private static final Logger log = LoggerFactory.getLogger(ToolRegistry.class);

	/**
	 * 越权参数名黑名单：模型如果试图传这些字段，一律剥离。
	 *
	 * <p>为什么用"剥离"而不是"拒绝整个调用"？
	 * 因为正常模型偶尔会把上下文里的字段原样复述进参数（尤其是小模型），
	 * 拒绝会造成误伤。而剥离是<b>无害且安全</b>的 —— 真正的身份永远来自
	 * {@link AgentToolContext}，剥掉之后调用照常按登录态执行。
	 *
	 * <p>但剥离动作必须留痕：{@code ai.tool.privilege_stripped} 指标一旦非零，
	 * 说明有人在尝试注入，这比一次 400 报警有用得多。
	 */
	private static final Set<String> PRIVILEGED_PARAMETERS = Set.of("userid", "user_id", "ownerid", "owner_id",
			"tenantid", "tenant_id", "accountid", "account_id", "username", "roles");

	private static final DateTimeFormatter FLEXIBLE_DATE_TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd[ ]['T']HH:mm[:ss]");

	private final ObjectMapper objectMapper;

	private final Map<String, RegisteredTool> tools = new LinkedHashMap<>();

	public ToolRegistry(ObjectMapper objectMapper, List<ToolProvider> providers) {
		this.objectMapper = objectMapper;
		for (ToolProvider provider : providers) {
			register(provider);
		}
		log.info("工具注册完成，共 {} 个：{}", tools.size(), tools.keySet());
	}

	// ------------------------------------------------------------------
	// 注册
	// ------------------------------------------------------------------

	private void register(Object bean) {
		for (Method method : bean.getClass().getMethods()) {
			Tool annotation = method.getAnnotation(Tool.class);
			if (annotation == null || method.isSynthetic()) {
				continue;
			}
			String name = annotation.name() == null || annotation.name().isBlank() ? method.getName()
					: annotation.name();
			if (tools.containsKey(name)) {
				// 重名工具会让模型的两个不同语义映射到同一个执行体，是最难排查的一类 bug
				throw new IllegalStateException("工具名重复: " + name + "，请检查 " + bean.getClass().getSimpleName());
			}
			List<ParamBinding> bindings = bindings(method);
			ToolSpec spec = new ToolSpec(name, annotation.description(), buildSchema(method, bindings),
					sideEffect(method, annotation));
			tools.put(name, new RegisteredTool(spec, bean, method, bindings));
			log.debug("注册工具 {} → {}.{}", name, bean.getClass().getSimpleName(), method.getName());
		}
	}

	/**
	 * 有副作用的工具怎么标记？
	 *
	 * <p>这里复用 {@code @Tool(resultConverter = ...)} 不合适，也没有语义。
	 * 本项目约定：<b>方法上标 {@code @SideEffect}</b>，或者返回类型是
	 * {@code ToolConfirmation}（待确认草稿）的自动视为有副作用。
	 * 用注解更显式，所以以注解为准。
	 */
	private boolean sideEffect(Method method, Tool annotation) {
		return method.isAnnotationPresent(SideEffect.class) || annotation.returnDirect();
	}

	private List<ParamBinding> bindings(Method method) {
		Parameter[] parameters = method.getParameters();
		List<ParamBinding> bindings = new ArrayList<>(parameters.length);
		for (int i = 0; i < parameters.length; i++) {
			Parameter parameter = parameters[i];
			if (parameter.getType() == AgentToolContext.class) {
				// 容器参数：注入，但绝不写进 schema
				bindings.add(new ParamBinding(i, "context", AgentToolContext.class, true, true, null));
				continue;
			}
			if (!parameter.isNamePresent()) {
				throw new IllegalStateException("工具方法 " + method.getName()
						+ " 的参数名不可见。请在 maven-compiler-plugin 中开启 <parameters>true</parameters>，"
						+ "否则模型看到的是 arg0 / arg1");
			}
			ToolParam param = parameter.getAnnotation(ToolParam.class);
			bindings.add(new ParamBinding(i, parameter.getName(), parameter.getType(), false,
					param == null || param.required(), param == null ? null : param.description()));
		}
		return bindings;
	}

	/**
	 * 生成 JSON Schema。
	 *
	 * <p>{@code additionalProperties: false} 是一道"软约束"：它告诉模型
	 * "别传多余字段"。遵守的模型会少犯错，不遵守的模型也伤不到我们 ——
	 * 执行时本来也只取 schema 内的字段。这类"给模型的约束 + 自己的兜底"
	 * 成对出现，是 AI 后端的基本功。
	 */
	private Map<String, Object> buildSchema(Method method, List<ParamBinding> bindings) {
		Map<String, Object> properties = new LinkedHashMap<>();
		List<String> required = new ArrayList<>();
		for (ParamBinding binding : bindings) {
			if (binding.context()) {
				continue;
			}
			Map<String, Object> property = new LinkedHashMap<>(typeSchema(binding.type()));
			if (binding.description() != null && !binding.description().isBlank()) {
				property.put("description", binding.description());
			}
			properties.put(binding.name(), property);
			if (binding.required()) {
				required.add(binding.name());
			}
		}
		Map<String, Object> schema = new LinkedHashMap<>();
		schema.put("type", "object");
		schema.put("properties", properties);
		if (!required.isEmpty()) {
			schema.put("required", required);
		}
		schema.put("additionalProperties", false);
		return schema;
	}

	private Map<String, Object> typeSchema(Class<?> type) {
		Map<String, Object> schema = new LinkedHashMap<>();
		if (type.isEnum()) {
			schema.put("type", "string");
			List<String> values = new ArrayList<>();
			for (Object constant : type.getEnumConstants()) {
				values.add(String.valueOf(constant));
			}
			schema.put("enum", values);
			return schema;
		}
		if (type == String.class || type == Character.class) {
			schema.put("type", "string");
		}
		else if (type == Integer.class || type == int.class || type == Long.class || type == long.class
				|| type == Short.class || type == short.class) {
			schema.put("type", "integer");
		}
		else if (type == Double.class || type == double.class || type == Float.class || type == float.class
				|| type == BigDecimal.class) {
			schema.put("type", "number");
		}
		else if (type == Boolean.class || type == boolean.class) {
			schema.put("type", "boolean");
		}
		else if (type == LocalDate.class) {
			schema.put("type", "string");
			schema.put("format", "date");
		}
		else if (type == LocalDateTime.class) {
			schema.put("type", "string");
			schema.put("format", "date-time");
		}
		else if (List.class.isAssignableFrom(type)) {
			schema.put("type", "array");
			schema.put("items", Map.of("type", "string"));
		}
		else {
			// 兜底成字符串而不是 object：宁可让模型传 JSON 字符串再由我们报错，
			// 也不要生成一个我们自己都解析不了的嵌套 schema
			schema.put("type", "string");
		}
		return schema;
	}

	// ------------------------------------------------------------------
	// 查询
	// ------------------------------------------------------------------

	/** 全部工具定义，按注册顺序（稳定顺序让 Prompt 前缀缓存更容易命中）。 */
	public List<ToolSpec> all() {
		return tools.values().stream().map(RegisteredTool::spec).toList();
	}

	public ToolSpec find(String name) {
		RegisteredTool tool = tools.get(name);
		return tool == null ? null : tool.spec();
	}

	public boolean contains(String name) {
		return tools.containsKey(name);
	}

	public int size() {
		return tools.size();
	}

	// ------------------------------------------------------------------
	// 执行
	// ------------------------------------------------------------------

	/**
	 * 执行一次工具调用。
	 *
	 * <p><b>这个方法永远不会抛异常</b>（除非 JVM 出问题）。所有失败都变成
	 * {@link ToolExecutionResult}，由上层决定是回喂模型还是终止。
	 */
	public ToolExecutionResult execute(String toolName, String argumentsJson, AgentToolContext context) {
		long startedAt = System.currentTimeMillis();
		RegisteredTool tool = tools.get(toolName);
		if (tool == null) {
			// 模型幻觉出一个不存在的工具：把可用工具列表告诉它，比直接报错有效
			return ToolExecutionResult.failure(toolName, "TOOL_NOT_FOUND",
					"工具 " + toolName + " 不存在。可用工具：" + tools.keySet(), elapsed(startedAt), List.of());
		}

		Map<String, Object> arguments;
		try {
			arguments = parseArguments(argumentsJson);
		}
		catch (RuntimeException ex) {
			return ToolExecutionResult.failure(toolName, "TOOL_ARGUMENT_INVALID",
					"工具参数不是合法 JSON 对象，请按 schema 重新生成", elapsed(startedAt), List.of());
		}

		List<String> stripped = stripPrivileged(arguments, tool);
		if (!stripped.isEmpty()) {
			log.warn("工具参数中出现越权字段并已剥离 tool={} fields={}", toolName, stripped);
		}

		Object[] values = new Object[tool.method().getParameterCount()];
		for (ParamBinding binding : tool.bindings()) {
			if (binding.context()) {
				values[binding.index()] = context;
				continue;
			}
			Object raw = arguments.get(binding.name());
			if (raw == null) {
				if (binding.required()) {
					return ToolExecutionResult.failure(toolName, "TOOL_ARGUMENT_INVALID",
							"缺少必填参数 " + binding.name() + "，请参考 schema 重新生成参数", elapsed(startedAt),
							stripped);
				}
				values[binding.index()] = null;
				continue;
			}
			try {
				values[binding.index()] = convert(raw, binding.type(), binding.name());
			}
			catch (RuntimeException ex) {
				return ToolExecutionResult.failure(toolName, "TOOL_ARGUMENT_INVALID", ex.getMessage(),
						elapsed(startedAt), stripped);
			}
		}

		try {
			Object result = tool.method().invoke(tool.bean(), values);
			String payload = result == null ? "null" : objectMapper.writeValueAsString(result);
			return ToolExecutionResult.ok(toolName, payload, elapsed(startedAt), stripped);
		}
		catch (InvocationTargetException ex) {
			return failure(toolName, ex.getCause(), startedAt, stripped);
		}
		catch (ReflectiveOperationException | RuntimeException ex) {
			log.error("工具调用失败 tool={}", toolName, ex);
			return ToolExecutionResult.failure(toolName, "TOOL_EXECUTION_ERROR",
					"工具执行失败，请换一种方式或告知用户稍后再试", elapsed(startedAt), stripped);
		}
	}

	/**
	 * 工具内部异常的翻译规则。
	 *
	 * <p>只把"业务异常"的 message 交给模型 —— 那是我们主动写的、面向用户的话。
	 * 其他异常（NPE、SQL 异常）一律替换成通用文案：模型会把 payload
	 * 原样复述给终端用户，堆栈里的包名就是内部结构泄漏。
	 */
	private ToolExecutionResult failure(String toolName, Throwable cause, long startedAt, List<String> stripped) {
		long latency = elapsed(startedAt);
		if (cause instanceof com.smartdesk.common.BusinessException business) {
			log.info("工具业务异常 tool={} message={}", toolName, business.getMessage());
			return ToolExecutionResult.failure(toolName, "TOOL_BUSINESS_ERROR", business.getMessage(), latency,
					stripped);
		}
		if (cause instanceof IllegalArgumentException illegal) {
			log.info("工具参数非法 tool={} message={}", toolName, illegal.getMessage());
			return ToolExecutionResult.failure(toolName, "TOOL_ARGUMENT_INVALID", illegal.getMessage(), latency,
					stripped);
		}
		log.error("工具执行出现未预期异常 tool={}", toolName, cause);
		return ToolExecutionResult.failure(toolName, "TOOL_EXECUTION_ERROR",
				"工具执行失败，请换一种方式或告知用户稍后再试", latency, stripped);
	}

	private Map<String, Object> parseArguments(String argumentsJson) {
		if (argumentsJson == null || argumentsJson.isBlank()) {
			return new LinkedHashMap<>();
		}
		@SuppressWarnings("unchecked")
		Map<String, Object> parsed = objectMapper.readValue(argumentsJson, Map.class);
		return parsed == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parsed);
	}

	/**
	 * 剥离越权参数。
	 *
	 * <p>注意：这里连"schema 里没有的字段"也不特意报错 —— 有的模型会习惯性
	 * 把整个对话上下文塞进参数（比如把 {@code {"user":"alice"}} 带上），
	 * 报错只会让对话卡死。剥离 + 留痕 + 身份仍取登录态，是代价最小的正确做法。
	 */
	private List<String> stripPrivileged(Map<String, Object> arguments, RegisteredTool tool) {
		Set<String> stripped = new LinkedHashSet<>();
		for (String key : new ArrayList<>(arguments.keySet())) {
			if (PRIVILEGED_PARAMETERS.contains(key.toLowerCase())) {
				arguments.remove(key);
				stripped.add(key);
				continue;
			}
			if (!tool.spec().parameterNames().contains(key)) {
				// 非 schema 字段：不是越权，但同样忽略。单独记 debug，避免日志噪音
				log.debug("忽略未声明的工具参数 tool={} key={}", tool.spec().name(), key);
			}
		}
		return List.copyOf(stripped);
	}

	/**
	 * 参数类型转换。
	 *
	 * <p>模型给的 JSON 只有 string/number/boolean 几种基本类型，而方法签名可能是
	 * {@code Integer}、{@code LocalDateTime}、枚举。转换失败必须给出<b>可操作的</b>提示
	 * （"status 必须是 TODO/IN_PROGRESS/DONE 之一"），模型才能自己改对；
	 * 只说"参数错误"，它只会换个方式再错一次。
	 */
	private Object convert(Object raw, Class<?> target, String name) {
		if (raw == null) {
			return null;
		}
		if (target.isInstance(raw)) {
			return raw;
		}
		if (target == String.class) {
			return String.valueOf(raw);
		}
		if (target == Integer.class || target == int.class) {
			return number(raw, name).intValue();
		}
		if (target == Long.class || target == long.class) {
			return number(raw, name).longValue();
		}
		if (target == Short.class || target == short.class) {
			return number(raw, name).shortValue();
		}
		if (target == Double.class || target == double.class) {
			return number(raw, name).doubleValue();
		}
		if (target == Float.class || target == float.class) {
			return number(raw, name).floatValue();
		}
		if (target == BigDecimal.class) {
			return number(raw, name);
		}
		if (target == Boolean.class || target == boolean.class) {
			if (raw instanceof Boolean bool) {
				return bool;
			}
			return Boolean.parseBoolean(String.valueOf(raw));
		}
		if (target == LocalDate.class) {
			return parseDate(String.valueOf(raw), name);
		}
		if (target == LocalDateTime.class) {
			return parseDateTime(String.valueOf(raw), name);
		}
		if (target.isEnum()) {
			return parseEnum(raw, target, name);
		}
		throw new IllegalArgumentException("参数 " + name + " 的类型无法识别：" + target.getSimpleName());
	}

	private BigDecimal number(Object raw, String name) {
		try {
			return new BigDecimal(String.valueOf(raw).strip());
		}
		catch (NumberFormatException ex) {
			throw new IllegalArgumentException("参数 " + name + " 必须是数字，收到：" + raw);
		}
	}

	@SuppressWarnings({ "unchecked", "rawtypes" })
	private Object parseEnum(Object raw, Class<?> target, String name) {
		String text = String.valueOf(raw).strip();
		List<String> allowed = new ArrayList<>();
		for (Object constant : target.getEnumConstants()) {
			allowed.add(String.valueOf(constant));
		}
		for (Object constant : target.getEnumConstants()) {
			if (String.valueOf(constant).equalsIgnoreCase(text)) {
				return (Enum) constant;
			}
		}
		throw new IllegalArgumentException("参数 " + name + " 必须是 " + String.join("/", allowed) + " 之一，收到："
				+ text);
	}

	private LocalDate parseDate(String text, String name) {
		try {
			return LocalDate.parse(text.strip());
		}
		catch (DateTimeParseException ex) {
			throw new IllegalArgumentException("参数 " + name + " 必须是 yyyy-MM-dd 格式的日期，收到：" + text);
		}
	}

	/**
	 * 宽松解析日期时间。
	 *
	 * <p>真实模型给的格式五花八门：{@code 2026-11-07T18:00:00}、
	 * {@code 2026-11-07 18:00}、{@code 2026-11-07}、带时区的
	 * {@code 2026-11-07T18:00:00+08:00}。前三种在本项目里都能接受，
	 * 第四种直接截掉时区 —— 用户的时间语义是本地时间，不做时区换算反而更符合直觉。
	 */
	private LocalDateTime parseDateTime(String text, String name) {
		String normalized = text.strip();
		try {
			return LocalDateTime.parse(normalized);
		}
		catch (DateTimeParseException ignored) {
			// 继续尝试更宽松的格式
		}
		try {
			return LocalDateTime.parse(normalized, FLEXIBLE_DATE_TIME);
		}
		catch (DateTimeParseException ignored) {
			// 继续
		}
		try {
			return LocalDate.parse(normalized).atTime(18, 0);
		}
		catch (DateTimeParseException ignored) {
			// 到这儿说明真的不是日期
		}
		throw new IllegalArgumentException("参数 " + name + " 必须是 ISO-8601 时间（如 2026-11-07T18:00:00），收到：" + text);
	}

	private static long elapsed(long startedAt) {
		return Math.max(1, System.currentTimeMillis() - startedAt);
	}

	// ------------------------------------------------------------------
	// 内部结构
	// ------------------------------------------------------------------

	private record RegisteredTool(ToolSpec spec, Object bean, Method method, List<ParamBinding> bindings) {
	}

	/**
	 * 参数绑定。
	 *
	 * @param context 是否为容器参数（{@link AgentToolContext}）—— 是则不出现在 schema 里
	 */
	private record ParamBinding(int index, String name, Class<?> type, boolean context, boolean required,
			String description) {
	}

}
