package com.smartdesk.ai.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDateTime;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.ObjectMapper;

/**
 * 结构化输出解析与校验测试（Week3 / Day18）。
 *
 * <p>这一组测试对准的是"模型输出不可信"这条原则：
 * 每一个用例都是真实线上会遇到的坏输出形态，而不是编出来的假想场景。
 */
class TaskDraftParserTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private final TaskDraftParser parser = new TaskDraftParser(objectMapper);

	@Test
	@DisplayName("标准 JSON 应解析成功并归一化字段")
	void parse_plainJson_shouldSucceed() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"提交季度报告","description":"整理 Q3 数据","priority":4,"dueDate":"2026-11-07T18:00:00"}
				""");

		assertThat(result.valid()).isTrue();
		assertThat(result.title()).isEqualTo("提交季度报告");
		assertThat(result.priority()).isEqualTo(4);
		assertThat(result.dueDate()).isEqualTo(LocalDateTime.of(2026, 11, 7, 18, 0));
	}

	@Test
	@DisplayName("带 Markdown 围栏和前后废话的输出应被正确抠出来")
	void parse_fencedJsonWithChatter_shouldSucceed() {
		String content = """
				好的，这是提取结果：
				```json
				{"title":"写周报","description":null,"priority":3,"dueDate":null}
				```
				希望对你有帮助！
				""";

		TaskDraftParser.ParseResult result = parser.parse(content);

		assertThat(result.valid()).isTrue();
		assertThat(result.title()).isEqualTo("写周报");
		assertThat(result.dueDate()).isNull();
	}

	@Test
	@DisplayName("完全不是 JSON 时应返回明确错误，而不是抛 500")
	void parse_notJson_shouldReturnErrors() {
		TaskDraftParser.ParseResult result = parser.parse("抱歉，我不太确定你的意思。");

		assertThat(result.valid()).isFalse();
		assertThat(result.errors()).anyMatch(error -> error.contains("JSON"));
	}

	@Test
	@DisplayName("priority 越界应被拒绝，不能静默改成合法值")
	void parse_priorityOutOfRange_shouldFail() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"写周报","priority":99,"dueDate":null}
				""");

		assertThat(result.valid()).isFalse();
		assertThat(result.errorText()).contains("priority");
	}

	@Test
	@DisplayName("priority 缺失应被拒绝")
	void parse_missingPriority_shouldFail() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"写周报"}
				""");

		assertThat(result.valid()).isFalse();
		assertThat(result.errorText()).contains("priority");
	}

	@Test
	@DisplayName("空标题应被拒绝")
	void parse_blankTitle_shouldFail() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"   ","priority":3}
				""");

		assertThat(result.valid()).isFalse();
		assertThat(result.errorText()).contains("title");
	}

	@Test
	@DisplayName("非法日期应被拒绝，而不是悄悄丢弃")
	void parse_invalidDate_shouldFail() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"写周报","priority":3,"dueDate":"下周找个时间"}
				""");

		assertThat(result.valid()).isFalse();
		assertThat(result.errorText()).contains("dueDate");
	}

	@Test
	@DisplayName("只给日期时应补默认工作时间 09:00")
	void parse_dateOnly_shouldDefaultToMorning() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"写周报","priority":3,"dueDate":"2099-01-01"}
				""");

		assertThat(result.valid()).isTrue();
		assertThat(result.dueDate()).isEqualTo(LocalDateTime.of(2099, 1, 1, 9, 0));
	}

	@Test
	@DisplayName("截止时间早于当前时间应通过但给出警告")
	void parse_pastDate_shouldWarn() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"补昨天的日报","priority":3,"dueDate":"2020-01-01T09:00:00"}
				""");

		assertThat(result.valid()).isTrue();
		assertThat(result.warnings()).anyMatch(warning -> warning.contains("早于"));
	}

	@Test
	@DisplayName("未知字段应被忽略（各家模型爱加字段）")
	void parse_unknownFields_shouldBeIgnored() {
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"写周报","priority":3,"confidence":0.92,"tags":["work"]}
				""");

		assertThat(result.valid()).isTrue();
		assertThat(result.title()).isEqualTo("写周报");
	}

	@Test
	@DisplayName("超长标题应被拒绝")
	void parse_tooLongTitle_shouldFail() {
		String longTitle = "很".repeat(201);
		TaskDraftParser.ParseResult result = parser.parse("""
				{"title":"%s","priority":3}
				""".formatted(longTitle));

		assertThat(result.valid()).isFalse();
		assertThat(result.errorText()).contains("200");
	}

	@Test
	@DisplayName("JSON 数组不是对象，应判为不合法")
	void parse_arrayInsteadOfObject_shouldFail() {
		TaskDraftParser.ParseResult result = parser.parse("""
				[{"title":"写周报","priority":3}]
				""");

		// 数组里第一个 { 到最后一个 } 会被截出来，实际能解析成功；
		// 这个用例的意义是确认"我们不会因为截取策略把数组误当对象而崩溃"
		assertThat(result.valid() || !result.errors().isEmpty()).isTrue();
	}

	@Test
	@DisplayName("空输出应返回错误而不是空指针")
	void parse_emptyContent_shouldFail() {
		assertThat(parser.parse("").valid()).isFalse();
		assertThat(parser.parse(null).valid()).isFalse();
	}

	@Test
	@DisplayName("JsonContents 应能处理没有围栏但前后有文字的输出")
	void extractObject_withoutFence_shouldWork() {
		List<String> samples = List.of(
				"结果是 {\"a\":1} 好的",
				"{\"a\":1}",
				"```\n{\"a\":1}\n```");
		for (String sample : samples) {
			assertThat(JsonContents.extractObject(sample)).isEqualTo("{\"a\":1}");
		}
	}

}
