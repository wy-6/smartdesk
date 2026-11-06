package com.smartdesk.knowledge.dto;

import java.util.List;

import com.smartdesk.ai.dto.AiMeta;

/**
 * 知识库问答结果（Week5 / Day33）。
 *
 * @param question 原始问题
 * @param answer 回答正文（含 [n] 形式的引用标记）
 * @param refused 是否拒答（检索不到足够证据）
 * @param citations 引用列表（由检索结果生成，与编号一一对应）
 * @param retrievalCount 参与检索的片段数
 * @param unsupportedMarkers 模型引用了但检索结果里不存在的编号（引用错误）
 * @param meta 调用元信息
 */
public record AskResponse(String question, String answer, boolean refused, List<Citation> citations,
		int retrievalCount, List<Integer> unsupportedMarkers, AiMeta meta) {

}
