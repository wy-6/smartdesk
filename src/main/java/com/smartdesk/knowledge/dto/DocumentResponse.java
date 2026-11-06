package com.smartdesk.knowledge.dto;

import java.time.LocalDateTime;

import com.smartdesk.knowledge.domain.KnowledgeDocument;

/**
 * 知识库文档响应（Week5 / Day30）。
 *
 * <p><b>只返回元数据，不返回切片正文</b>：列表页不需要正文，
 * 带正文会让响应体随文档量线性膨胀。
 *
 * @param id 文档 id
 * @param title 标题
 * @param fileName 原始文件名
 * @param sourceType 来源类型
 * @param charCount 正文字符数
 * @param chunkCount 切片数
 * @param status 状态：INDEXED / FAILED
 * @param duplicate 是否为重复导入（命中已有文档，未重复建索引）
 * @param createdAt 创建时间
 */
public record DocumentResponse(Long id, String title, String fileName, String sourceType,
		Integer charCount, Integer chunkCount, String status, boolean duplicate,
		LocalDateTime createdAt) {

	/**
	 * 实体 → 响应。
	 *
	 * @param entity 知识库文档实体
	 * @param duplicate 是否为重复导入命中
	 */
	public static DocumentResponse from(KnowledgeDocument entity, boolean duplicate) {
		return new DocumentResponse(entity.getId(), entity.getTitle(), entity.getFileName(),
				entity.getSourceType(), entity.getCharCount(), entity.getChunkCount(),
				entity.getStatus(), duplicate, entity.getCreatedAt());
	}

}
