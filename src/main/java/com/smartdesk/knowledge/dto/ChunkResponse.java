package com.smartdesk.knowledge.dto;

/**
 * 检索到的知识片段（Week5 / Day32）。
 *
 * <p><b>这就是"独立检索流程"的产出</b>：不经过模型，就能直接看到
 * 检索到了什么、来自哪份文档的第几片、相似度多少。
 *
 * <p><b>为什么必须把分数暴露出来？</b>
 * 因为 Top-K 一定有 K 个结果，哪怕一个都不相关。
 * 没有分数，前端和用户都判断不了"这是真命中还是凑数"，
 * 排查召回问题也无从下手。这是第 5 周 Day32 的验收点。
 *
 * @param chunkId 切片 id
 * @param documentId 文档 id
 * @param documentTitle 文档标题
 * @param chunkIndex 文档内切片序号
 * @param pageNo 页码（PDF 才有，其它为 null）
 * @param content 切片正文
 * @param score 相似度分数
 * @param charStart 在原文中的起始偏移
 * @param charEnd 在原文中的结束偏移
 */
public record ChunkResponse(Long chunkId, Long documentId, String documentTitle, Integer chunkIndex,
		Integer pageNo, String content, double score, Integer charStart, Integer charEnd) {

	/** 引用展示用的简短摘要：截前 200 字。 */
	public String snippet() {
		if (content == null) {
			return "";
		}
		String single = content.replaceAll("\\s+", " ").strip();
		return single.length() > 200 ? single.substring(0, 200) + "…" : single;
	}

}
