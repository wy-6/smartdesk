package com.smartdesk.knowledge.dto;

import java.util.List;

/**
 * 知识库文档详情（Week5 / Day32）。
 *
 * <p>详情页要能看到"这份文档被切成了什么"，这是调试切分策略的入口：
 * 切得不好时，一眼就能看出是不是把一个意思劈成了两半。
 *
 * @param document 文档元数据
 * @param chunks 切片列表
 */
public record DocumentDetailResponse(DocumentResponse document, List<ChunkResponse> chunks) {

}
