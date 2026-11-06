package com.smartdesk.knowledge.store;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.VectorStore;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import com.baomidou.mybatisplus.core.toolkit.Wrappers;
import com.smartdesk.knowledge.domain.KnowledgeChunk;
import com.smartdesk.knowledge.mapper.KnowledgeChunkMapper;

/**
 * 基于 MySQL 的向量存储（Week5 / Day31-32）。
 *
 * <p><b>实现 Spring AI 官方的 {@link VectorStore} 接口</b>，
 * 而不是自己另造一套。好处：业务代码依赖的是 Spring AI 的抽象，
 * 将来换成 pgvector / Milvus / Qdrant，只需要换实现类，检索代码一行不改。
 *
 * <p><b>为什么不用官方的 SimpleVectorStore？</b>
 * <ol>
 *   <li>SimpleVectorStore 是内存 + JSON 文件持久化，<b>过滤在内存里做</b>。
 *       本项目的硬约定是"过滤必须下沉到 SQL" —— 检索语句里必须能看到
 *       {@code owner_id} 条件，而不是查出来再筛</li>
 *   <li>它没有"删除某个用户的全部知识"这种语义</li>
 *   <li>本机 Docker 不可用，起不了 Milvus / Qdrant / pgvector</li>
 * </ol>
 *
 * <p><b>本实现的检索流程：</b>
 * <ol>
 *   <li>把查询文本向量化</li>
 *   <li><b>SQL 过滤</b>：{@code WHERE owner_id = ?}</li>
 *   <li>对过滤后的候选集逐个算余弦相似度</li>
 *   <li>按相似度阈值过滤、按分数排序、取 Top-K</li>
 * </ol>
 * 相似度在 Java 侧算而不是 SQL 侧算，是因为 MySQL 8.0 没有内置向量距离函数
 * （真正好用的是 HeatWave 的 VECTOR 索引）。<b>这是精确检索，不是近似检索（ANN）</b>：
 * 候选集小的时候精确检索更准，代价是要把候选行都读出来。
 *
 * <p><b>安全红线：</b>{@link #similaritySearch(SearchRequest)} <b>强制要求</b>
 * 过滤表达式里带 {@code owner_id}。没有 owner 过滤的检索一律拒绝执行 ——
 * 这不是"建议"，是硬约束。漏了就是越权。
 */
@Component
public class MySqlKnowledgeVectorStore implements VectorStore {

	private static final Logger log = LoggerFactory.getLogger(MySqlKnowledgeVectorStore.class);

	/** 元数据里的归属人字段名。 */
	public static final String META_OWNER_ID = "owner_id";

	/** 元数据里的文档 id 字段名。 */
	public static final String META_DOCUMENT_ID = "document_id";

	/** 元数据里的切片序号字段名。 */
	public static final String META_CHUNK_INDEX = "chunk_index";

	/** 元数据里的页码字段名。 */
	public static final String META_PAGE_NO = "page_no";

	private final KnowledgeChunkMapper chunkMapper;

	private final EmbeddingModel embeddingModel;

	/** 单次检索最多读取多少候选行，防止一次查询把整库拖出来。 */
	private final int maxCandidates;

	/**
	 * 注入用构造器。
	 *
	 * <p>本类有两个构造器，Spring 无法自动选择，所以必须显式标注 {@code @Autowired} ——
	 * 否则启动时报 "No default constructor found"，而且这个错误只出现在集成测试里
	 * （单元测试用 new，不走容器）。
	 */
	@Autowired
	public MySqlKnowledgeVectorStore(KnowledgeChunkMapper chunkMapper, EmbeddingModel embeddingModel) {
		this(chunkMapper, embeddingModel, 5000);
	}

	public MySqlKnowledgeVectorStore(KnowledgeChunkMapper chunkMapper, EmbeddingModel embeddingModel,
			int maxCandidates) {
		this.chunkMapper = chunkMapper;
		this.embeddingModel = embeddingModel;
		this.maxCandidates = maxCandidates;
	}

	@Override
	public void add(List<Document> documents) {
		if (documents == null || documents.isEmpty()) {
			return;
		}
		for (Document document : documents) {
			Map<String, Object> metadata = document.getMetadata();
			Long ownerId = longValue(metadata, META_OWNER_ID);
			Long documentId = longValue(metadata, META_DOCUMENT_ID);
			if (ownerId == null || documentId == null) {
				// 缺归属信息 = 无法保证隔离，宁可拒收也不能写进库
				throw new IllegalArgumentException("写入向量库必须带 " + META_OWNER_ID + " 与 " + META_DOCUMENT_ID + " 元数据");
			}
			float[] vector = embeddingModel.embed(document);
			KnowledgeChunk chunk = new KnowledgeChunk();
			chunk.setOwnerId(ownerId);
			chunk.setDocumentId(documentId);
			chunk.setChunkIndex(intValue(metadata, META_CHUNK_INDEX, 0));
			chunk.setContent(document.getText());
			chunk.setPageNo(intValueOrNull(metadata, META_PAGE_NO));
			chunk.setCharStart(intValue(metadata, "char_start", 0));
			chunk.setCharEnd(intValue(metadata, "char_end", 0));
			chunk.setEmbedding(VectorCodec.encode(vector));
			chunk.setEmbeddingDim(vector.length);
			chunkMapper.insert(chunk);
		}
	}

	@Override
	public void delete(List<String> idList) {
		// 故意不支持：按裸 id 删除没有 owner 校验，是越权入口。
		// 需要删除请走 KnowledgeService#delete(ownerId, documentId)，
		// 它先校验归属再按 (owner_id, document_id) 删。
		throw new UnsupportedOperationException("禁止按裸 id 批量删除，请使用按 owner 校验的删除方法");
	}

	@Override
	public void delete(Filter.Expression filterExpression) {
		Long ownerId = extractOwnerId(filterExpression);
		if (ownerId == null) {
			throw new IllegalArgumentException("删除必须带 " + META_OWNER_ID + " 过滤条件");
		}
		chunkMapper.delete(Wrappers.<KnowledgeChunk>lambdaQuery().eq(KnowledgeChunk::getOwnerId, ownerId));
	}

	@Override
	public List<Document> similaritySearch(SearchRequest request) {
		Long ownerId = extractOwnerId(request.getFilterExpression());
		if (ownerId == null) {
			throw new IllegalArgumentException("检索必须带 " + META_OWNER_ID + " 过滤条件（这是隔离的硬约束，不允许绕过）");
		}
		float[] queryVector = embeddingModel.embed(request.getQuery());

		// ★ 过滤发生在 SQL 里：owner_id 是 WHERE 条件，不是查出来再筛
		List<KnowledgeChunk> candidates = chunkMapper.selectList(Wrappers.<KnowledgeChunk>lambdaQuery()
				.eq(KnowledgeChunk::getOwnerId, ownerId)
				.last("LIMIT " + maxCandidates));

		List<Document> hits = new ArrayList<>();
		int dimensionMismatch = 0;
		for (KnowledgeChunk chunk : candidates) {
			// 换过 embedding 模型后，旧向量的维度对不上，算出来的相似度毫无意义。
			// 这种行直接跳过（并计数），而不是当成 0 分混进结果里。
			if (chunk.getEmbeddingDim() == null || chunk.getEmbeddingDim() != queryVector.length) {
				dimensionMismatch++;
				continue;
			}
			float[] vector = VectorCodec.decode(chunk.getEmbedding());
			double score = VectorCodec.cosine(queryVector, vector);
			if (score < request.getSimilarityThreshold()) {
				continue;
			}
			// 注意：Spring AI 的 Document.Builder 不接受 null 元数据值，
			// 所以 page_no 为空（TXT/Markdown）时不能直接 metadata(k, null)。
			Document.Builder builder = Document.builder()
					.id(String.valueOf(chunk.getId()))
					.text(chunk.getContent())
					.metadata(META_OWNER_ID, chunk.getOwnerId())
					.metadata(META_DOCUMENT_ID, chunk.getDocumentId())
					.metadata(META_CHUNK_INDEX, chunk.getChunkIndex())
					.metadata("char_start", chunk.getCharStart())
					.metadata("char_end", chunk.getCharEnd())
					.metadata("chunk_id", chunk.getId())
					.score(score);
			if (chunk.getPageNo() != null) {
				builder.metadata(META_PAGE_NO, chunk.getPageNo());
			}
			hits.add(builder.build());
		}
		if (dimensionMismatch > 0) {
			log.warn("有 {} 个切片的向量维度与当前 embedding 模型不一致，已跳过（需要重建索引）", dimensionMismatch);
		}
		hits.sort(Comparator.comparingDouble((Document d) -> d.getScore() == null ? 0 : d.getScore()).reversed());
		int topK = request.getTopK();
		return hits.size() > topK ? new ArrayList<>(hits.subList(0, topK)) : hits;
	}

	/**
	 * 从过滤表达式里取出 {@code owner_id}。
	 *
	 * <p>只支持最简单也最常用的一种：{@code owner_id == 值}。
	 * 遇到 AND / OR / IN 等复杂表达式直接拒绝 —— 宁可报错，
	 * 也不要"解析不出来就当没有过滤"（那等于放开越权）。
	 */
	private Long extractOwnerId(Filter.Expression expression) {
		if (expression == null) {
			return null;
		}
		if (expression.type() != Filter.ExpressionType.EQ) {
			return null;
		}
		Filter.Operand left = expression.left();
		Filter.Operand right = expression.right();
		if (!(left instanceof Filter.Key key) || !(right instanceof Filter.Value value)) {
			return null;
		}
		if (!META_OWNER_ID.equals(key.key())) {
			return null;
		}
		Object raw = value.value();
		if (raw instanceof Number number) {
			return number.longValue();
		}
		if (raw instanceof String text) {
			try {
				return Long.parseLong(text);
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	private Long longValue(Map<String, Object> metadata, String key) {
		Object value = metadata == null ? null : metadata.get(key);
		if (value instanceof Number number) {
			return number.longValue();
		}
		if (value instanceof String text) {
			try {
				return Long.parseLong(text);
			}
			catch (NumberFormatException ex) {
				return null;
			}
		}
		return null;
	}

	private int intValue(Map<String, Object> metadata, String key, int fallback) {
		Object value = metadata == null ? null : metadata.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		return fallback;
	}

	private Integer intValueOrNull(Map<String, Object> metadata, String key) {
		Object value = metadata == null ? null : metadata.get(key);
		if (value instanceof Number number) {
			return number.intValue();
		}
		return null;
	}

}
