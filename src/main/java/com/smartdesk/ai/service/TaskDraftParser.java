package com.smartdesk.ai.service;

import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import tools.jackson.databind.ObjectMapper;

/**
 * 任务草稿解析 + 校验（Week3 / Day18）。
 *
 * <p><b>这是全周最重要的一条工程原则：模型输出是不可信输入。</b>
 *
 * <p>为什么不能把模型返回的 JSON 直接映射成实体然后 insert？
 * <ol>
 *   <li><b>字段可能缺失</b>：title 为空 → 数据库 NOT NULL 报错，用户看到 500</li>
 *   <li><b>类型可能不对</b>：priority 返回 {@code "很高"} → 反序列化直接炸</li>
 *   <li><b>值可能越界</b>：priority = 99 → 业务规则被破坏，排序全乱</li>
 *   <li><b>日期可能是编的</b>：用户没提时间，模型"贴心"补了一个下周 —— 这是幻觉</li>
 *   <li><b>可能带注入内容</b>：title 里塞一段指令，第 4 周工具调用时会被当成指令执行</li>
 * </ol>
 *
 * <p>所以流程固定为：<b>解析 → 逐字段校验 → 全部通过才允许交给业务层</b>。
 * 校验失败不是抛 500，而是把错误回喂给模型修复（有次数上限），
 * 修不好就明确返回 422，让用户重说一遍。
 */
@Component
public class TaskDraftParser {

	/** 标题长度上限，与 {@code task.title} 列宽一致。 */
	private static final int MAX_TITLE = 200;

	/** 描述长度上限，与 {@code task.description} 列宽一致。 */
	private static final int MAX_DESCRIPTION = 2000;

	private static final DateTimeFormatter[] DATE_TIME_FORMATS = {
			DateTimeFormatter.ISO_LOCAL_DATE_TIME,
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
			DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
	};

	private final ObjectMapper objectMapper;

	public TaskDraftParser(ObjectMapper objectMapper) {
		this.objectMapper = objectMapper;
	}

	/**
	 * 解析并校验。
	 *
	 * <p>不抛异常：把"哪里不合法"作为数据返回，让调用方决定怎么补救。
	 * 这是"校验"和"异常"的正确分工 —— 校验失败是可预期的分支，不是崩溃。
	 */
	public ParseResult parse(String content) {
		String json = JsonContents.extractObject(content);
		if (json == null) {
			return ParseResult.invalid(List.of("输出中没有找到 JSON 对象"));
		}

		TaskDraftPayload payload;
		try {
			payload = objectMapper.readValue(json, TaskDraftPayload.class);
		}
		catch (RuntimeException ex) {
			return ParseResult.invalid(List.of("JSON 解析失败：" + firstLine(ex.getMessage())));
		}

		List<String> errors = new ArrayList<>();
		List<String> warnings = new ArrayList<>();

		String title = payload.title() == null ? "" : payload.title().strip();
		if (title.isEmpty()) {
			errors.add("title 不能为空");
		}
		else if (title.length() > MAX_TITLE) {
			errors.add("title 超过 " + MAX_TITLE + " 字");
		}

		String description = payload.description() == null ? null : payload.description().strip();
		if (description != null && description.length() > MAX_DESCRIPTION) {
			errors.add("description 超过 " + MAX_DESCRIPTION + " 字");
		}

		Integer priority = payload.priority();
		if (priority == null) {
			errors.add("priority 缺失");
		}
		else if (priority < 1 || priority > 5) {
			errors.add("priority 必须在 1-5 之间，实际为 " + priority);
		}

		LocalDateTime dueDate = null;
		String rawDue = payload.dueDate();
		if (rawDue != null && !rawDue.isBlank() && !"null".equalsIgnoreCase(rawDue.strip())) {
			dueDate = parseDateTime(rawDue.strip());
			if (dueDate == null) {
				errors.add("dueDate 不是合法时间：" + rawDue);
			}
			else if (dueDate.isBefore(LocalDateTime.now())) {
				// 不拦：用户可能就是想把已过期的事记下来；但必须提示
				warnings.add("截止时间早于当前时间");
			}
		}

		if (!errors.isEmpty()) {
			return new ParseResult(null, null, null, null, errors, warnings);
		}
		return new ParseResult(title, description, priority, dueDate, errors, warnings);
	}

	private static LocalDateTime parseDateTime(String text) {
		for (DateTimeFormatter format : DATE_TIME_FORMATS) {
			try {
				return LocalDateTime.parse(text, format);
			}
			catch (DateTimeParseException ignored) {
				// 继续试下一种格式
			}
		}
		// 兼容 "2026-11-07"：给一个默认工作时间，而不是丢成 null
		try {
			return LocalDate.parse(text).atTime(9, 0);
		}
		catch (DateTimeParseException ignored) {
			return null;
		}
	}

	private static String firstLine(String message) {
		if (message == null) {
			return "未知原因";
		}
		int index = message.indexOf('\n');
		return index < 0 ? message : message.substring(0, index);
	}

	/**
	 * 模型返回的原始结构。
	 *
	 * <p>字段全部用包装类型 + String：因为我们要先"原样接住"，
	 * 再自己判断类型是否合法。如果直接声明成 {@code Integer priority}，
	 * 模型给 {@code "3"} 或 {@code "很高"} 时反序列化就会抛异常，
	 * 我们就失去了"告诉模型哪里错了"的机会。
	 */
	@JsonIgnoreProperties(ignoreUnknown = true)
	public record TaskDraftPayload(String title, String description, Integer priority, String dueDate) {
	}

	/** 解析结果。{@code draft} 为 {@code null} 表示校验未通过。 */
	public record ParseResult(String title, String description, Integer priority, LocalDateTime dueDate,
			List<String> errors, List<String> warnings) {

		static ParseResult invalid(List<String> errors) {
			return new ParseResult(null, null, null, null, errors, List.of());
		}

		public boolean valid() {
			return errors.isEmpty();
		}

		public String errorText() {
			return String.join("; ", errors);
		}

	}

}
