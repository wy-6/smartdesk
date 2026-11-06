package com.smartdesk.knowledge.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 知识库文档（Week5 / Day30）。
 *
 * <p><b>元数据设计说明</b>：
 * <ul>
 *   <li>{@code ownerId}：归属人，<b>来自 JWT，绝不接受请求体传入</b>。
 *       检索时必须带它，这是多租户隔离的根</li>
 *   <li>{@code contentHash}：正文内容的 SHA-256。用来做<b>重复导入判定</b> ——
 *       同一份制度反复上传不应该产生两份索引（Day35 的验收项之一）</li>
 *   <li>{@code chunkCount}：冗余字段。列表页要显示"这份文档切成了多少片"，
 *       没有它就得 N+1 查询每份文档的切片数</li>
 *   <li>{@code status}：{@code INDEXED} 已建索引 / {@code FAILED} 建索引失败。
 *       失败也要留记录，否则用户传了个加密 PDF 只会看到"没反应"</li>
 * </ul>
 *
 * <p>注意这里<b>不存正文</b>：正文切完片就只存在于 kb_chunk 里。
 * 正文留着会重复占空间，而且会让"删文档"这件事变复杂（正文和切片两处都要清）。
 */
@TableName("kb_document")
public class KnowledgeDocument {

	@TableId(type = IdType.AUTO)
	private Long id;

	/** 归属用户，来自 JWT。 */
	private Long ownerId;

	/** 文档标题（用户可自定义，默认取文件名）。 */
	private String title;

	/** 原始文件名，保留用于引用溯源展示。 */
	private String fileName;

	/** 来源类型：TEXT / MARKDOWN / PDF。 */
	private String sourceType;

	/** 正文的 SHA-256 十六进制串，用于重复导入判定。 */
	private String contentHash;

	/** 正文字符数。 */
	private Integer charCount;

	/** 切片数量。 */
	private Integer chunkCount;

	/** 状态：INDEXED / FAILED。 */
	private String status;

	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createdAt;

	@TableField(fill = FieldFill.INSERT_UPDATE)
	private LocalDateTime updatedAt;

	public Long getId() {
		return id;
	}

	public void setId(Long id) {
		this.id = id;
	}

	public Long getOwnerId() {
		return ownerId;
	}

	public void setOwnerId(Long ownerId) {
		this.ownerId = ownerId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getFileName() {
		return fileName;
	}

	public void setFileName(String fileName) {
		this.fileName = fileName;
	}

	public String getSourceType() {
		return sourceType;
	}

	public void setSourceType(String sourceType) {
		this.sourceType = sourceType;
	}

	public String getContentHash() {
		return contentHash;
	}

	public void setContentHash(String contentHash) {
		this.contentHash = contentHash;
	}

	public Integer getCharCount() {
		return charCount;
	}

	public void setCharCount(Integer charCount) {
		this.charCount = charCount;
	}

	public Integer getChunkCount() {
		return chunkCount;
	}

	public void setChunkCount(Integer chunkCount) {
		this.chunkCount = chunkCount;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

	public LocalDateTime getUpdatedAt() {
		return updatedAt;
	}

	public void setUpdatedAt(LocalDateTime updatedAt) {
		this.updatedAt = updatedAt;
	}

}
