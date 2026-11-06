package com.smartdesk.ai.tool;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.tool.annotation.Tool;
import org.springframework.ai.tool.annotation.ToolParam;

import tools.jackson.databind.ObjectMapper;

/**
 * {@link ToolRegistry} 单元测试（Week4 / Day23-24）。
 *
 * <p><b>这个测试类守的是三件事，每一件都对应一个真实故障：</b>
 * <ol>
 *   <li><b>schema 里不能有上下文参数</b>——有的话模型就能填 userId，越权</li>
 *   <li><b>参数转换失败要给可操作的提示</b>——不然模型会换个姿势继续错</li>
 *   <li><b>工具执行永远不抛异常</b>——抛了就是 500，而模型本来能自己纠正</li>
 * </ol>
 */
@DisplayName("ToolRegistry：schema 生成与安全执行")
class ToolRegistryTest {

	private ToolRegistry registry;

	private final ObjectMapper objectMapper = new ObjectMapper();

	private AgentToolContext context;

	@BeforeEach
	void setUp() {
		registry = new ToolRegistry(objectMapper, List.of(new SampleTools()));
		context = new AgentToolContext(42L, "trace-test", null);
	}

	@Test
	@DisplayName("注册：@Tool 方法被扫描，方法名兜底为工具名")
	void registersTools() {
		// echo / namedTool / writeThing / boom / crash
		assertThat(registry.size()).isEqualTo(5);
		assertThat(registry.contains("echo")).isTrue();
		assertThat(registry.contains("namedTool")).isTrue();
	}

	@Test
	@DisplayName("schema：上下文参数绝不出现在 properties 里")
	void schemaExcludesContextParameter() {
		ToolSpec spec = registry.find("echo");
		@SuppressWarnings("unchecked")
		Map<String, Object> properties = (Map<String, Object>) spec.parameterSchema().get("properties");

		assertThat(properties).containsKeys("text", "count", "status");
		assertThat(properties).doesNotContainKey("context");
		assertThat(properties.keySet()).noneMatch(key -> key.toLowerCase().contains("user"));
	}

	@Test
	@DisplayName("schema：必填/可选、枚举值、additionalProperties 都要正确")
	@SuppressWarnings("unchecked")
	void schemaShape() {
		ToolSpec spec = registry.find("echo");
		Map<String, Object> properties = (Map<String, Object>) spec.parameterSchema().get("properties");

		// count 标了 required=false，不应出现在 required 数组
		assertThat((List<String>) spec.parameterSchema().get("required")).containsExactly("text");
		// 枚举必须穷举，否则模型会编一个不存在的值
		Map<String, Object> status = (Map<String, Object>) properties.get("status");
		assertThat(status).containsEntry("type", "string").containsEntry("enum", List.of("TODO", "DONE"));
		// 给模型的软约束：别传多余字段
		assertThat(spec.parameterSchema()).containsEntry("additionalProperties", false);
	}

	@Test
	@DisplayName("schema：sideEffect 由 @SideEffect 标记")
	void sideEffectFlag() {
		assertThat(registry.find("writeThing").sideEffect()).isTrue();
		assertThat(registry.find("echo").sideEffect()).isFalse();
	}

	@Test
	@DisplayName("执行：正常调用，上下文被注入，返回 JSON")
	void executesWithContext() {
		ToolExecutionResult result = registry.execute("echo", "{\"text\":\"hi\",\"count\":3}", context);

		assertThat(result.ok()).isTrue();
		assertThat(result.payload()).contains("\"text\":\"hi\"").contains("\"count\":3");
		// 上下文由容器注入，不是模型传的
		assertThat(result.payload()).contains("\"userId\":42");
	}

	@Test
	@DisplayName("执行：模型传 userId 会被剥离，且身份仍取登录态")
	void stripsPrivilegedParameters() {
		ToolExecutionResult result = registry.execute("echo",
				"{\"text\":\"hi\",\"userId\":999,\"ownerId\":888}", context);

		assertThat(result.ok()).isTrue();
		assertThat(result.privilegeStripped()).isTrue();
		assertThat(result.strippedParameters()).containsExactlyInAnyOrder("userId", "ownerId");
		// 关键断言：即使传了 999，工具看到的仍是登录态里的 42
		assertThat(result.payload()).contains("\"userId\":42").doesNotContain("999");
	}

	@Test
	@DisplayName("执行：不存在的工具返回可读错误，并列出可用工具")
	void unknownTool() {
		ToolExecutionResult result = registry.execute("dropDatabase", "{}", context);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorCode()).isEqualTo("TOOL_NOT_FOUND");
		assertThat(result.payload()).contains("echo");
	}

	@Test
	@DisplayName("执行：非法 JSON 不抛异常，返回 TOOL_ARGUMENT_INVALID")
	void invalidJson() {
		ToolExecutionResult result = registry.execute("echo", "{not json", context);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorCode()).isEqualTo("TOOL_ARGUMENT_INVALID");
	}

	@Test
	@DisplayName("执行：缺必填参数要指出是哪个参数")
	void missingRequired() {
		ToolExecutionResult result = registry.execute("echo", "{\"count\":1}", context);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorCode()).isEqualTo("TOOL_ARGUMENT_INVALID");
		assertThat(result.payload()).contains("text");
	}

	@Test
	@DisplayName("执行：类型转换失败要给出可操作提示（枚举列出合法值）")
	void invalidEnumValue() {
		ToolExecutionResult result = registry.execute("echo",
				"{\"text\":\"hi\",\"status\":\"逾期\"}", context);

		assertThat(result.ok()).isFalse();
		assertThat(result.payload()).contains("TODO/DONE");
	}

	@Test
	@DisplayName("执行：时间格式容错（空格分隔、纯日期都能解析）")
	void flexibleDateTime() {
		assertThat(registry.execute("echo", "{\"text\":\"a\",\"dueAt\":\"2026-11-07 18:00\"}", context).ok())
				.isTrue();
		assertThat(registry.execute("echo", "{\"text\":\"a\",\"dueAt\":\"2026-11-07\"}", context).ok()).isTrue();
	}

	@Test
	@DisplayName("执行：工具内部抛业务异常 → 转为可读错误，不向上抛")
	void businessExceptionIsConverted() {
		ToolExecutionResult result = registry.execute("boom", "{}", context);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorCode()).isEqualTo("TOOL_BUSINESS_ERROR");
		assertThat(result.payload()).isEqualTo("业务规则不允许这样做");
	}

	@Test
	@DisplayName("执行：工具抛未预期异常 → 通用文案，绝不泄露堆栈")
	void unexpectedExceptionIsMasked() {
		ToolExecutionResult result = registry.execute("crash", "{}", context);

		assertThat(result.ok()).isFalse();
		assertThat(result.errorCode()).isEqualTo("TOOL_EXECUTION_ERROR");
		assertThat(result.payload()).doesNotContain("NullPointer").doesNotContain("com.smartdesk");
	}

	/** 测试用工具集：覆盖各种参数类型与失败路径。 */
	static class SampleTools implements ToolProvider {

		@Tool(name = "echo", description = "回显参数，测试用")
		public Object echo(@ToolParam(description = "文本") String text,
				@ToolParam(required = false, description = "次数") Integer count,
				@ToolParam(required = false, description = "状态") SampleStatus status,
				@ToolParam(required = false, description = "时间") java.time.LocalDateTime dueAt,
				AgentToolContext context) {
			return Map.of("text", text, "count", count == null ? 0 : count, "status",
					status == null ? "NONE" : status.name(), "userId", context.userId());
		}

		@Tool(name = "namedTool", description = "显式命名的工具")
		public Object named(@ToolParam(description = "值") String value, AgentToolContext context) {
			return Map.of("value", value);
		}

		@Tool(name = "writeThing", description = "有副作用的工具")
		@SideEffect("写操作")
		public Object write(@ToolParam(description = "标题") String title, AgentToolContext context) {
			return Map.of("ok", true);
		}

		@Tool(name = "boom", description = "抛业务异常")
		public Object boom(AgentToolContext context) {
			throw new com.smartdesk.common.BusinessException("BIZ_ERROR", "业务规则不允许这样做");
		}

		@Tool(name = "crash", description = "抛未预期异常")
		public Object crash(AgentToolContext context) {
			throw new NullPointerException("数据库连接池空了：jdbc:mysql://secret-host");
		}

	}

	enum SampleStatus {

		TODO, DONE

	}

}
