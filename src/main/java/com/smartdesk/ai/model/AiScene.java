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
	TASK_DRAFT("task_draft"),

	/**
	 * Agent 工具调用（Week4）。
	 *
	 * <p>单独一个场景而不是复用 {@link #CHAT}，是因为它的成本结构和 chat 完全不同：
	 * 一次 agent 请求可能是 3 次模型调用 + 2 次工具执行。混在一起统计，
	 * 既看不出 agent 的真实成本，也算不准单次对话的 P99。
	 */
	AGENT("agent"),

	/**
	 * 知识库问答（RAG，Week5）。
	 *
	 * <p>单独一个场景，因为成本结构完全不同：一次 RAG 请求 = 1 次向量检索
	 * + 1 次模型调用，而且<b>检索结果决定要不要调模型</b> ——
	 * 检索为空时这次请求的模型成本是 0。混进 chat 场景统计，
	 * 就看不出"检索召回率"和"生成准确率"这两个关键指标。
	 */
	RAG("rag");

	private final String code;

	AiScene(String code) {
		this.code = code;
	}

	public String getCode() {
		return code;
	}

}
