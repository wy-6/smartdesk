package com.smartdesk.ai.dto;

/**
 * 一次工具调用的轨迹（Week4 / Day26）。
 *
 * <p><b>为什么要把工具调用轨迹返回给前端？</b>
 * 这是 Agent 应用"可解释性"的最低要求：用户看到"你有 3 条逾期任务"时，
 * 应该能点开看到"调了 listTasks，参数是 {onlyOverdue:true}，耗时 12ms"。
 * 没有这个，AI 的回答就是不可验证的断言。
 *
 * @param name 工具名
 * @param arguments 调用参数（已截断，避免响应体过大）
 * @param ok 是否执行成功
 * @param errorCode 失败错误码
 * @param latencyMs 执行耗时
 */
public record ToolCallTrace(String name, String arguments, boolean ok, String errorCode, long latencyMs) {

	private static final int MAX_ARGUMENTS = 300;

	public static ToolCallTrace of(String name, String arguments, boolean ok, String errorCode, long latencyMs) {
		return new ToolCallTrace(name, truncate(arguments), ok, errorCode, latencyMs);
	}

	private static String truncate(String text) {
		if (text == null) {
			return "{}";
		}
		String single = text.replaceAll("\\s+", " ").strip();
		return single.length() > MAX_ARGUMENTS ? single.substring(0, MAX_ARGUMENTS) + "…" : single;
	}

}
