package com.smartdesk.ai.tool;

import java.util.List;

/**
 * 一次工具执行的结果（Week4 / Day26）。
 *
 * <p><b>关键设计：工具失败不抛异常，而是返回一个"失败结果"。</b>
 *
 * <p>这是工具调用和普通 Service 调用最大的区别。普通 Service 里查不到数据，
 * 抛异常让全局异常处理器转成 404 是对的；但工具执行失败时，
 * <b>正确的做法是把错误信息回喂给模型，让它自己纠正</b>：
 * <pre>
 * 模型：listTasks(status="逾期")            ← 编了个不存在的枚举值
 * 工具：{"ok":false,"error":"status 必须是 TODO/IN_PROGRESS/DONE 之一"}
 * 模型：listTasks(status="TODO", onlyOverdue=true)   ← 自己改对了
 * </pre>
 * 如果直接抛异常，用户看到的是 500，但模型本来有能力自己修好。
 *
 * <p><b>但错误文案必须脱敏</b>：SQL 异常、堆栈、类名一律不能出现在 payload 里。
 * 模型会把这些内容原样复述给用户，等于把内部结构泄露出去。
 *
 * @param toolName 工具名
 * @param ok 是否成功
 * @param payload 给模型看的字符串（成功的 JSON 结果，或失败的错误说明）
 * @param errorCode 失败时的错误码，用于指标与审计
 * @param latencyMs 执行耗时
 * @param strippedParameters 被剥离的越权参数名（模型试图传 userId 之类）
 */
public record ToolExecutionResult(String toolName, boolean ok, String payload, String errorCode, long latencyMs,
		List<String> strippedParameters) {

	public static ToolExecutionResult ok(String toolName, String payload, long latencyMs,
			List<String> strippedParameters) {
		return new ToolExecutionResult(toolName, true, payload, null, latencyMs, strippedParameters);
	}

	public static ToolExecutionResult failure(String toolName, String errorCode, String message, long latencyMs,
			List<String> strippedParameters) {
		return new ToolExecutionResult(toolName, false, message, errorCode, latencyMs, strippedParameters);
	}

	/** 是否发生过越权参数注入尝试。 */
	public boolean privilegeStripped() {
		return strippedParameters != null && !strippedParameters.isEmpty();
	}

}
