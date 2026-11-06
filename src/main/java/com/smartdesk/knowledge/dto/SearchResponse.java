package com.smartdesk.knowledge.dto;

import java.util.List;

/**
 * 知识检索结果（Week5 / Day32）。
 *
 * @param query 原始查询
 * @param topK 实际使用的 Top-K
 * @param threshold 实际使用的相似度阈值
 * @param count 命中片段数
 * @param hits 命中片段（含分数）
 */
public record SearchResponse(String query, int topK, double threshold, int count, List<ChunkResponse> hits) {

}
