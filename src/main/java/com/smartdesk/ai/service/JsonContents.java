package com.smartdesk.ai.service;

/**
 * 从模型输出里"抠"出 JSON（Week3 / Day18）。
 *
 * <p><b>为什么不能直接 {@code readValue(content)}？</b>
 * 因为现实中模型输出长这样：
 * <pre>
 * 好的，这是提取结果：
 * ```json
 * {"title":"写周报","priority":3}
 * ```
 * 希望对你有帮助！
 * </pre>
 * 你要求"只输出 JSON"，它答应的概率大概 80%。剩下 20% 就是线上 422 的来源。
 *
 * <p>处理策略（从宽容到严格，逐级降级）：
 * <ol>
 *   <li>剥掉 Markdown 代码块围栏</li>
 *   <li>截取第一个 {@code &#123;} 到最后一个 {@code &#125;} 之间 —— 能容忍前后废话</li>
 *   <li>仍然解析不了 → 抛 {@code AI_INVALID_OUTPUT}，由上层决定"补问"还是"拒答"</li>
 * </ol>
 *
 * <p><b>面试可讲：这不是"容错设计"，而是"对不可信输入的防御"。</b>
 * 模型输出要当作不可信外部输入处理，和对待用户表单提交一样的严格。
 */
public final class JsonContents {

	private JsonContents() {
	}

	/**
	 * 截取输出中的 JSON 对象文本。
	 *
	 * @param content 模型原始输出
	 * @return JSON 文本；找不到返回 {@code null}
	 */
	public static String extractObject(String content) {
		if (content == null) {
			return null;
		}
		String text = content.strip();
		if (text.isEmpty()) {
			return null;
		}

		// 1) 剥代码块围栏：```json ... ``` 或 ``` ... ```
		int fence = text.indexOf("```");
		if (fence >= 0) {
			int lineEnd = text.indexOf('\n', fence);
			int close = lineEnd < 0 ? -1 : text.indexOf("```", lineEnd + 1);
			if (lineEnd > 0 && close > lineEnd) {
				text = text.substring(lineEnd + 1, close).strip();
			}
		}

		// 2) 截取第一个 { 到最后一个 }
		int start = text.indexOf('{');
		int end = text.lastIndexOf('}');
		if (start < 0 || end <= start) {
			return null;
		}
		return text.substring(start, end + 1);
	}

}
