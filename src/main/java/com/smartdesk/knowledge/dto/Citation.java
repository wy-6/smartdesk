package com.smartdesk.knowledge.dto;

/**
 * 引用（Week5 / Day33）。
 *
 * <p><b>引用由检索结果生成，不是由模型生成。</b>
 * 这是第 5 周最重要的一个设计决策：
 * <ul>
 *   <li>模型的回答里写 {@code [1]}，只是它"声称"用了第 1 条资料</li>
 *   <li>我们的 citations 列表来自真实检索到的片段，<b>编号与片段一一对应</b></li>
 *   <li>如果模型编了一个 {@code [99]}，我们能立刻发现（99 不在 citations 里），
 *       这叫"引用错误"，可以在评测里统计</li>
 * </ul>
 * 反过来，如果让模型自己编引用，它会编得很像 —— 格式对、编号连续、
 * 但对应的文本根本不存在，用户点进去才发现是空的。
 *
 * @param index 引用编号（从 1 开始，与 Prompt 里的资料编号一致）
 * @param chunkId 切片 id
 * @param documentId 文档 id
 * @param documentTitle 文档标题
 * @param chunkIndex 切片序号
 * @param pageNo 页码
 * @param snippet 片段摘要
 * @param score 相似度分数
 */
public record Citation(int index, Long chunkId, Long documentId, String documentTitle,
		Integer chunkIndex, Integer pageNo, String snippet, double score) {

}
