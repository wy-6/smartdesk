package com.smartdesk.ai.model;

/**
 * AI 调用场景（Week3 / Day17）。
 *
 * <p>为什么要给调用打场景标签？
 * <ul>
 *   <li><b>可观测</b>：{@code ai.calls{scene="task_draft",outcome="error"}} 能直接看出
 *       是哪个功能在烧钱、哪个功能失败率高</li>
 *   <li><b>可治理</b>：不同场景的超时、重试、Token 上限可以分开配</li>
 *   <li><b>可评测</b>：Prompt 迭代时按场景跑测试集，避免"改摘要把分类搞坏了"</li>
 * </ul>
 */
public enum AiScene {

	/** 自由问答。 */
	CHAT("chat"),

	/** 文本摘要。 */
	SUMMARY("summary"),

	/** 工单分类。 */
	CLASSIFY("classify"),

	/** 自然语言 → 任务草稿（结构化输出）。 */
	TASK_DRAFT("task_draft");

	private final String code;

	AiScene(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

}
