package com.smartdesk.knowledge.service;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartdesk.common.BusinessException;
import com.smartdesk.common.ResourceNotFoundException;
import com.smartdesk.knowledge.config.RagProperties;
import com.smartdesk.knowledge.domain.KnowledgeChunk;
import com.smartdesk.knowledge.domain.KnowledgeDocument;
import com.smartdesk.knowledge.domain.SourceType;
import com.smartdesk.knowledge.dto.ChunkResponse;
import com.smartdesk.knowledge.dto.DocumentDetailResponse;
import com.smartdesk.knowledge.dto.DocumentResponse;
import com.smartdesk.knowledge.mapper.KnowledgeChunkMapper;
import com.smartdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.smartdesk.knowledge.service.DocumentTextExtractor.PageText;
import com.smartdesk.knowledge.store.Chunker;
import com.smartdesk.knowledge.store.MySqlKnowledgeVectorStore;
import com.smartdesk.knowledge.store.TextChunk;

/**
 * 知识库文档服务（Week5 / Day30-31）。
 *
 * <p><b>入库流程（Day31 的"索引流程"）</b>：
 * <pre>
 * 原始字节
 *   → 解析成文本（按页）
 *   → 内容哈希（重复导入判定）
 *   → 切片（大小/重叠可配置）
 *   → 向量化（Embedding 模型）
 *   → 写入 kb_chunk（owner_id 冗余存储）
 *   → 更新 kb_document 的切片数
 * </pre>
 *
 * <p><b>三个关键取舍：</b>
 * <ol>
 *   <li><b>重复导入不重新建索引</b>：内容哈希命中已有文档就直接返回。
 *       重新向量化一遍要花钱（embedding 也是计费的），而且会产生两份一模一样的索引</li>
 *   <li><b>文档和切片一起提交事务</b>：不能出现"文档建好了但切片只有一半"的中间态 ——
 *       那样用户检索时会得到残缺答案，而且完全看不出哪里出了问题</li>
 *   <li><b>owner_id 从不来自请求</b>：所有方法第一个参数就是 ownerId，
 *       由 Controller 从 JWT 取出来传进来</li>
 * </ol>
 */
@Service
public class KnowledgeService {

	private final KnowledgeDocumentMapper documentMapper;

	private final KnowledgeChunkMapper chunkMapper;

	private final VectorStore vectorStore;

	private final Chunker chunker;

	private final DocumentTextExtractor extractor;

	private final RagProperties properties;

	public KnowledgeService(KnowledgeDocumentMapper documentMapper, KnowledgeChunkMapper chunkMapper,
			VectorStore vectorStore, Chunker chunker, DocumentTextExtractor extractor, RagProperties properties) {
		this.documentMapper = documentMapper;
		this.chunkMapper = chunkMapper;
		this.vectorStore = vectorStore;
		this.chunker = chunker;
		this.extractor = extractor;
		this.properties = properties;
	}

	/**
	 * 创建文档并建立索引。
	 *
	 * @param ownerId 归属人（来自 JWT）
	 * @param title 标题
	 * @param fileName 原始文件名，可为 null
	 * @param sourceType 来源类型
	 * @param content 原始字节
	 * @return 文档元数据；{@code duplicate=true} 表示命中已有文档，未重复建索引
	 */
	@Transactional
	public DocumentResponse create(Long ownerId, String title, String fileName, SourceType sourceType,
			byte[] content) {
		List<PageText> pages = extractor.extract(content, sourceType);
		String fullText = pages.stream().map(PageText::text).reduce("", (a, b) -> a + "\n" + b).strip();
		if (fullText.isEmpty()) {
			throw new BusinessException("KNOWLEDGE_DOCUMENT_EMPTY", "文档没有可索引的内容");
		}
		if (fullText.length() > properties.getMaxDocumentChars()) {
			throw new BusinessException("KNOWLEDGE_DOCUMENT_TOO_LARGE",
					"文档超过 " + properties.getMaxDocumentChars() + " 字，请拆分后再导入");
		}

		String hash = sha256(fullText);
		KnowledgeDocument existing = documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
				.eq(KnowledgeDocument::getOwnerId, ownerId)
				.eq(KnowledgeDocument::getContentHash, hash)
				.last("LIMIT 1"));
		if (existing != null) {
			// 重复导入：直接返回已有文档，不重新向量化（省 token，也避免两份一样的索引）
			return DocumentResponse.from(existing, true);
		}

		KnowledgeDocument document = new KnowledgeDocument();
		document.setOwnerId(ownerId);
		document.setTitle(title);
		document.setFileName(fileName);
		document.setSourceType(sourceType.name());
		document.setContentHash(hash);
		document.setCharCount(fullText.length());
		document.setChunkCount(0);
		document.setStatus("INDEXED");
		documentMapper.insert(document);

		int chunkIndex = 0;
		for (PageText page : pages) {
			for (TextChunk chunk : chunker.split(page.text())) {
				if (chunkIndex >= properties.getMaxChunksPerDocument()) {
					break;
				}
				Document vectorDocument = Document.builder()
						.text(chunk.content())
						.metadata(MySqlKnowledgeVectorStore.META_OWNER_ID, ownerId)
						.metadata(MySqlKnowledgeVectorStore.META_DOCUMENT_ID, document.getId())
						.metadata("chunk_index", chunkIndex)
						.metadata("page_no", page.pageNo())
						.metadata("char_start", chunk.charStart())
						.metadata("char_end", chunk.charEnd())
						.build();
				vectorStore.add(List.of(vectorDocument));
				chunkIndex++;
			}
		}
		if (chunkIndex == 0) {
			// 没有切出任何片段：把文档标记为失败并抛错，不要留一个"看起来导入成功"的空文档
			document.setStatus("FAILED");
			documentMapper.updateById(document);
			throw new BusinessException("KNOWLEDGE_DOCUMENT_EMPTY", "文档切分后没有有效内容");
		}

		document.setChunkCount(chunkIndex);
		documentMapper.updateById(document);
		return DocumentResponse.from(document, false);
	}

	/**
	 * 我的文档列表。
	 */
	@Transactional(readOnly = true)
	public List<DocumentResponse> list(Long ownerId, int pageNum, int pageSize) {
		List<KnowledgeDocument> documents = documentMapper.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
				.eq(KnowledgeDocument::getOwnerId, ownerId)
				.orderByDesc(KnowledgeDocument::getCreatedAt)
				.last("LIMIT " + pageSize + " OFFSET " + Math.max(0, (pageNum - 1) * pageSize)));
		return documents.stream().map(doc -> DocumentResponse.from(doc, false)).toList();
	}

	/**
	 * 文档详情（含切片）。
	 */
	@Transactional(readOnly = true)
	public DocumentDetailResponse detail(Long ownerId, Long documentId) {
		KnowledgeDocument document = requireDocument(ownerId, documentId);
		List<KnowledgeChunk> chunks = chunkMapper.selectList(Wrappers.<KnowledgeChunk>lambdaQuery()
				.eq(KnowledgeChunk::getOwnerId, ownerId)
				.eq(KnowledgeChunk::getDocumentId, documentId)
				.orderByAsc(KnowledgeChunk::getChunkIndex));
		List<ChunkResponse> responses = new ArrayList<>();
		for (KnowledgeChunk chunk : chunks) {
			responses.add(new ChunkResponse(chunk.getId(), document.getId(), document.getTitle(),
					chunk.getChunkIndex(), chunk.getPageNo(), chunk.getContent(), 0,
					chunk.getCharStart(), chunk.getCharEnd()));
		}
		return new DocumentDetailResponse(DocumentResponse.from(document, false), responses);
	}

	/**
	 * 删除文档（连同它的切片）。
	 *
	 * <p>切片表的 {@code document_id} 上有 {@code ON DELETE CASCADE}，
	 * 所以删文档时切片会跟着删。但我们仍然显式删一次 —— 依赖级联的行为，
	 * 数据库换了实现（比如从 MySQL 换到别的库）就可能失效。
	 */
	@Transactional
	public void delete(Long ownerId, Long documentId) {
		KnowledgeDocument document = requireDocument(ownerId, documentId);
		chunkMapper.delete(Wrappers.<KnowledgeChunk>lambdaQuery()
				.eq(KnowledgeChunk::getOwnerId, ownerId)
				.eq(KnowledgeChunk::getDocumentId, documentId));
		documentMapper.deleteById(document.getId());
	}

	/**
	 * 取文档，不存在或不属于当前用户都返回 404。
	 *
	 * <p><b>为什么不是 403？</b>第 1 周的规矩：返回 404 而不是 403，
	 * 不泄露"这个 id 存在但不是你的"这种信息。存在性和归属权一起隐藏。
	 */
	@Transactional(readOnly = true)
	public KnowledgeDocument requireDocument(Long ownerId, Long documentId) {
		KnowledgeDocument document = documentMapper.selectOne(Wrappers.<KnowledgeDocument>lambdaQuery()
				.eq(KnowledgeDocument::getId, documentId)
				.eq(KnowledgeDocument::getOwnerId, ownerId)
				.last("LIMIT 1"));
		if (document == null) {
			throw new ResourceNotFoundException("知识库文档不存在: " + documentId);
		}
		return document;
	}

	/** 内容哈希（SHA-256 十六进制）。 */
	private String sha256(String text) {
		try {
			MessageDigest digest = MessageDigest.getInstance("SHA-256");
			return HexFormat.of().formatHex(digest.digest(text.getBytes(StandardCharsets.UTF_8)));
		}
		catch (NoSuchAlgorithmException ex) {
			throw new IllegalStateException("JVM 不支持 SHA-256", ex);
		}
	}

}
