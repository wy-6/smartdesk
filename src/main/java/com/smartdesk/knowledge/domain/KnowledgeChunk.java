package com.smartdesk.knowledge.domain;

import java.time.LocalDateTime;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;

/**
 * 知识库切片（Week5 / Day30-31）。
 *
 * <p><b>为什么切片表要冗余 {@code ownerId}？</b>
 * 表面看可以从 document_id 关联查出 owner_id，多一次 JOIN 而已。但：
 * <ul>
 *   <li><b>检索热路径</b>：每次检索都要按 owner_id 过滤片段。
 *       JOIN 会让优化器多一层，owner_id 直接建索引更快</li>
 *   <li><b>防御性纵深</b>：第 1 周定下的规矩是"SQL 层再兜一层"。
 *       万一将来有人写错 JOIN 条件，冗余列还能挡住一次越权</li>
 * </ul>
 *
 * <p><b>embedding 存成 BLOB 而不是 JSON 文本：</b>
 * 256 维 float32 是 1KB。存成 JSON 文本要 6KB 左右（每个数字带逗号和引号），
 * 存进 BLOB 是二进制紧凑格式，读取时直接 {@code ByteBuffer} 解析成 float[]，
 * 没有字符串解析开销。数据库里也不需要人能看懂它。
 */
@TableName("kb_chunk")
public class KnowledgeChunk {

	@TableId(type = IdType.AUTO)
	private Long id;

	/** 归属用户，来自 JWT（冗余列，检索时直接用它过滤）。 */
	private Long ownerId;

	/** 所属文档 id。 */
	private Long documentId;

	/** 文档内的切片序号，从 0 开始。 */
	private Integer chunkIndex;

	/** 切片正文。 */
	private String content;

	/** 页码（PDF 才有），用于引用溯源时告诉用户翻到第几页。 */
	private Integer pageNo;

	/** 切片在原文中的起始字符偏移。用于回溯原文位置。 */
	private Integer charStart;

	/** 切片在原文中的结束字符偏移（不含）。 */
	private Integer charEnd;

	/** 向量数据（float32 小端字节序）。 */
	private byte[] embedding;

	/** 向量维度，写进表里是为了以后换模型时能识别出"这批向量不是同一个模型产的"。 */
	private Integer embeddingDim;

	@TableField(fill = FieldFill.INSERT)
	private LocalDateTime createdAt;

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

	public Long getDocumentId() {
		return documentId;
	}

	public void setDocumentId(Long documentId) {
		this.documentId = documentId;
	}

	public Integer getChunkIndex() {
		return chunkIndex;
	}

	public void setChunkIndex(Integer chunkIndex) {
		this.chunkIndex = chunkIndex;
	}

	public String getContent() {
		return content;
	}

	public void setContent(String content) {
		this.content = content;
	}

	public Integer getPageNo() {
		return pageNo;
	}

	public void setPageNo(Integer pageNo) {
		this.pageNo = pageNo;
	}

	public Integer getCharStart() {
		return charStart;
	}

	public void setCharStart(Integer charStart) {
		this.charStart = charStart;
	}

	public Integer getCharEnd() {
		return charEnd;
	}

	public void setCharEnd(Integer charEnd) {
		this.charEnd = charEnd;
	}

	public byte[] getEmbedding() {
		return embedding;
	}

	public void setEmbedding(byte[] embedding) {
		this.embedding = embedding;
	}

	public Integer getEmbeddingDim() {
		return embeddingDim;
	}

	public void setEmbeddingDim(Integer embeddingDim) {
		this.embeddingDim = embeddingDim;
	}

	public LocalDateTime getCreatedAt() {
		return createdAt;
	}

	public void setCreatedAt(LocalDateTime createdAt) {
		this.createdAt = createdAt;
	}

}
