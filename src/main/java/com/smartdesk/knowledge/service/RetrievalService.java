package com.smartdesk.knowledge.service;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;
import org.springframework.stereotype.Service;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartdesk.knowledge.config.RagProperties;
import com.smartdesk.knowledge.domain.KnowledgeDocument;
import com.smartdesk.knowledge.dto.ChunkResponse;
import com.smartdesk.knowledge.mapper.KnowledgeDocumentMapper;
import com.smartdesk.knowledge.store.MySqlKnowledgeVectorStore;

/**
 * 知识检索服务（Week5 / Day32）。
 *
 * <p><b>这是"独立的检索流程"</b>：不经过大模型就能返回命中片段。
 * 它的存在让三个问题变得可回答：
 * <ul>
 *   <li>模型答错了，是<b>没检索到</b>还是<b>检索到了但没用好</b>？</li>
 *   <li>检索本身准不准？（第 5 周评测集直接打这个接口）</li>
 *   <li>引用对不对？（片段 id、分数、来源文件都能看到）</li>
 * </ul>
 *
 * <p><b>为什么要有"单文档限流"？</b>
 * 如果用户上传了 10 份内容高度相似的制度文档，Top-K 很可能全被同一份文档占满，
 * 检索结果看起来"很多"，实际只有一个信息源。所以每个文档最多贡献
 * {@code max-chunks-per-document} 个片段，剩下的名额留给其它文档。
 */
@Service
public class RetrievalService {

	private final VectorStore vectorStore;

	private final KnowledgeDocumentMapper documentMapper;

	private final RagProperties properties;

	public RetrievalService(VectorStore vectorStore, KnowledgeDocumentMapper documentMapper,
			RagProperties properties) {
		this.vectorStore = vectorStore;
		this.documentMapper = documentMapper;
		this.properties = properties;
	}

	/**
	 * 检索。
	 *
	 * @param ownerId 归属人（来自 JWT）
	 * @param query 查询语句
	 * @param topK 召回数量，null 用配置默认值
	 * @param threshold 相似度阈值，null 用配置默认值
	 * @return 命中片段（按分数从高到低）
	 */
	public List<ChunkResponse> search(Long ownerId, String query, Integer topK, Double threshold) {
		return searchDetailed(ownerId, query, topK, threshold).hits();
	}

	/**
	 * 检索（带实际生效的参数）。
	 *
	 * <p>把 topK / threshold 的<b>实际值</b>一起返回，是为了让接口响应能自解释：
	 * 客户端传 topK=100，服务端夹到 20，那响应里就该显示 20 而不是 100。
	 * 否则用户看到"我传了 100，怎么只回来 5 条"会以为是 bug。
	 *
	 * @param ownerId 归属人（来自 JWT）
	 * @param query 查询语句
	 * @param topK 召回数量，null 用配置默认值
	 * @param threshold 相似度阈值，null 用配置默认值
	 * @return 检索结果 + 实际参数
	 */
	public SearchResult searchDetailed(Long ownerId, String query, Integer topK, Double threshold) {
		int resolvedTopK = resolveTopK(topK);
		double resolvedThreshold = resolveThreshold(threshold);

		// ★ owner 过滤是硬约束：没有它，向量库会直接拒绝检索
		Filter.Expression filter = new FilterExpressionBuilder()
				.eq(MySqlKnowledgeVectorStore.META_OWNER_ID, ownerId)
				.build();

		SearchRequest request = SearchRequest.builder()
				.query(query)
				.topK(resolvedTopK)
				.similarityThreshold(resolvedThreshold)
				.filterExpression(filter)
				.build();

		List<Document> documents = vectorStore.similaritySearch(request);
		documents = limitPerDocument(documents);
		// 上面的 similaritySearch 已经按 topK 截断过；这里再按解析后的值兜一次

		Map<Long, String> titles = loadTitles(ownerId, documents);

		List<ChunkResponse> hits = new ArrayList<>();
		for (Document document : documents) {
			Map<String, Object> metadata = document.getMetadata();
			Long documentId = longValue(metadata, MySqlKnowledgeVectorStore.META_DOCUMENT_ID);
			Long chunkId = longValue(metadata, "chunk_id");
			Integer chunkIndex = intValue(metadata, "chunk_index");
			Integer pageNo = intValue(metadata, "page_no");
			Integer charStart = intValue(metadata, "char_start");
			Integer charEnd = intValue(metadata, "char_end");
			double score = document.getScore() == null ? 0 : document.getScore();
			hits.add(new ChunkResponse(chunkId, documentId, titles.get(documentId), chunkIndex, pageNo,
					document.getText(), score, charStart, charEnd));
		}
		return new SearchResult(resolvedTopK, resolvedThreshold, hits);
	}

	/**
	 * 检索结果 + 实际生效的参数。
	 *
	 * @param topK 实际使用的 Top-K
	 * @param threshold 实际使用的相似度阈值
	 * @param hits 命中片段
	 */
	public record SearchResult(int topK, double threshold, List<ChunkResponse> hits) {
	}

	/**
	 * 单文档限流：每个文档最多贡献 N 个片段。
	 */
	private List<Document> limitPerDocument(List<Document> documents) {
		int maxPerDocument = properties.getMaxChunksPerDocument();
		Map<Long, Integer> counter = new HashMap<>();
		List<Document> limited = new ArrayList<>();
		for (Document document : documents) {
			Long documentId = longValue(document.getMetadata(), MySqlKnowledgeVectorStore.META_DOCUMENT_ID);
			int count = counter.merge(documentId, 1, Integer::sum);
			if (count <= maxPerDocument) {
				limited.add(document);
			}
		}
		return limited;
	}

	/**
	 * 批量取文档标题。
	 *
	 * <p>注意这里也带 owner 条件：虽然切片已经按 owner 过滤过了，
	 * 但"文档标题"是另一张表的数据，<b>每张表都要各自守好自己的边界</b>。
	 */
	private Map<Long, String> loadTitles(Long ownerId, List<Document> documents) {
		Map<Long, String> titles = new LinkedHashMap<>();
		List<Long> documentIds = documents.stream()
				.map(d -> longValue(d.getMetadata(), MySqlKnowledgeVectorStore.META_DOCUMENT_ID))
				.filter(id -> id != null)
				.distinct()
				.toList();
		if (documentIds.isEmpty()) {
			return titles;
		}
		List<KnowledgeDocument> docs = documentMapper.selectList(Wrappers.<KnowledgeDocument>lambdaQuery()
				.eq(KnowledgeDocument::getOwnerId, ownerId)
				.in(KnowledgeDocument::getId, documentIds));
		for (KnowledgeDocument doc : docs) {
			titles.put(doc.getId(), doc.getTitle());
		}
		return titles;
	}

	private int resolveTopK(Integer topK) {
		int value = topK == null ? properties.getTopK() : topK;
		if (value < 1) {
			return 1;
		}
		return Math.min(value, properties.getMaxTopK());
	}

	private double resolveThreshold(Double threshold) {
		if (threshold == null) {
			return properties.getSimilarityThreshold();
		}
		if (threshold < 0) {
			return 0;
		}
		return Math.min(threshold, 1);
	}

	private Long longValue(Map<String, Object> metadata, String key) {
		Object value = metadata == null ? null : metadata.get(key);
		return value instanceof Number number ? number.longValue() : null;
	}

	private Integer intValue(Map<String, Object> metadata, String key) {
		Object value = metadata == null ? null : metadata.get(key);
		return value instanceof Number number ? number.intValue() : null;
	}

}
