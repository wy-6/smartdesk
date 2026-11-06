package com.smartdesk.knowledge.store;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.List;

import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;
import org.springframework.ai.vectorstore.SearchRequest;
import org.springframework.ai.vectorstore.filter.Filter;
import org.springframework.ai.vectorstore.filter.FilterExpressionBuilder;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.smartdesk.knowledge.domain.KnowledgeChunk;
import com.smartdesk.knowledge.mapper.KnowledgeChunkMapper;

/**
 * 向量存储测试（Week5 / Day31-32）。
 *
 * <p><b>本类最重要的断言是第一个：没有 owner 过滤的检索必须被拒绝。</b>
 * 这是整个知识库的隔离红线 —— 一旦放开，任何人搜一下就能看到所有人的制度文件。
 * 所以这条不是"顺手测一下"，而是<b>模块存在的意义</b>。
 *
 * <p>为了精确控制相似度，这里用一个<b>可编程的桩嵌入模型</b>，
 * 而不是 mock 的哈希嵌入 —— 哈希模型的分值不可预测，测试会变成碰运气。
 */
class MySqlKnowledgeVectorStoreTest {

	private final KnowledgeChunkMapper chunkMapper = org.mockito.Mockito.mock(KnowledgeChunkMapper.class);

	/** 查询向量恒为 {1,0,0,0}，这样片段向量的相似度完全由片段自己决定。 */
	private final EmbeddingModel embeddingModel = new StubEmbeddingModel(new float[] { 1f, 0f, 0f, 0f });

	/**
	 * 初始化 MyBatis-Plus 的 Lambda 缓存。
	 *
	 * <p>纯单元测试里没有 Spring 上下文，Mapper 没被注册，
	 * {@code LambdaQueryWrapper} 解析 {@code KnowledgeChunk::getOwnerId} 时
	 * 找不到表元数据就会报 "can not find lambda cache"。
	 * 手动初始化一次，纯单元测试也能验证"SQL 里确实带了 owner_id"。
	 */
	@BeforeAll
	static void initMybatisPlusLambdaCache() {
		MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
		assistant.setCurrentNamespace(MySqlKnowledgeVectorStoreTest.class.getName());
		TableInfoHelper.initTableInfo(assistant, KnowledgeChunk.class);
	}

	private MySqlKnowledgeVectorStore store() {
		return new MySqlKnowledgeVectorStore(chunkMapper, embeddingModel);
	}

	@Test
	@DisplayName("越权红线：不带 owner 过滤的检索一律拒绝执行")
	void rejectsSearchWithoutOwnerFilter() {
		SearchRequest request = SearchRequest.builder()
				.query("报销期限")
				.topK(5)
				.similarityThreshold(0.1)
				.build();

		assertThatThrownBy(() -> store().similaritySearch(request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("owner_id");
	}

	@Test
	@DisplayName("越权红线：过滤条件不是 owner_id 也不能放行")
	void rejectsSearchWithForeignFilter() {
		Filter.Expression filter = new FilterExpressionBuilder().eq("document_id", 7).build();
		SearchRequest request = SearchRequest.builder()
				.query("报销期限")
				.filterExpression(filter)
				.build();

		assertThatThrownBy(() -> store().similaritySearch(request))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("owner_id");
	}

	@Test
	@DisplayName("删除接口不开放按裸 id 删除（越权入口）")
	void rejectsDeleteByRawIds() {
		assertThatThrownBy(() -> store().delete(List.of("1", "2")))
				.isInstanceOf(UnsupportedOperationException.class)
				.hasMessageContaining("owner");
	}

	@Test
	@DisplayName("写入必须带归属元数据，否则拒收")
	void rejectsAddWithoutOwnerMetadata() {
		Document document = Document.builder()
				.text("没有归属信息的内容")
				.metadata("chunk_index", 0)
				.build();

		assertThatThrownBy(() -> store().add(List.of(document)))
				.isInstanceOf(IllegalArgumentException.class)
				.hasMessageContaining("owner_id");
	}

	@Test
	@DisplayName("检索语句在 SQL 层带 owner_id 条件（过滤下沉到 SQL）")
	void filtersOwnerInSql() {
		when(chunkMapper.selectList(any())).thenReturn(List.of());

		Filter.Expression filter = new FilterExpressionBuilder().eq("owner_id", 42L).build();
		SearchRequest request = SearchRequest.builder()
				.query("报销期限")
				.topK(5)
				.similarityThreshold(0.1)
				.filterExpression(filter)
				.build();

		store().similaritySearch(request);

		@SuppressWarnings("unchecked")
		ArgumentCaptor<Wrapper<KnowledgeChunk>> captor = ArgumentCaptor.forClass(Wrapper.class);
		verify(chunkMapper).selectList(captor.capture());
		String sql = captor.getValue().getTargetSql();
		assertThat(sql).contains("owner_id");
	}

	@Test
	@DisplayName("低于阈值的片段被过滤掉，只返回够格的")
	void appliesSimilarityThreshold() {
		when(chunkMapper.selectList(any())).thenReturn(List.of(
				chunk(1L, "报销期限是 30 天", 1f, 0f, 0f, 0f),
				chunk(2L, "数据库连接池配置说明", 0f, 1f, 0f, 0f)));

		SearchRequest request = request(5, 0.5);

		List<Document> hits = store().similaritySearch(request);

		assertThat(hits).hasSize(1);
		assertThat(hits.get(0).getText()).isEqualTo("报销期限是 30 天");
		assertThat(hits.get(0).getScore()).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
	}

	@Test
	@DisplayName("检索结果按相似度从高到低排序，并遵守 Top-K")
	void sortsAndLimits() {
		when(chunkMapper.selectList(any())).thenReturn(List.of(
				chunk(1L, "中等相关片段", 0.6f, 0.8f, 0f, 0f),
				chunk(2L, "最相关片段", 1f, 0f, 0f, 0f),
				chunk(3L, "不相关片段", 0f, 0f, 1f, 0f)));

		SearchRequest request = request(2, 0.0);

		List<Document> hits = store().similaritySearch(request);

		assertThat(hits).hasSize(2);
		assertThat(hits.get(0).getText()).isEqualTo("最相关片段");
		assertThat(hits.get(1).getText()).isEqualTo("中等相关片段");
	}

	@Test
	@DisplayName("向量维度对不上的旧切片被跳过，不混进结果")
	void skipsDimensionMismatch() {
		KnowledgeChunk stale = chunk(3L, "旧模型的向量", 1f, 0f, 0f, 0f);
		stale.setEmbeddingDim(1024);
		KnowledgeChunk fresh = chunk(4L, "报销期限是 30 天", 1f, 0f, 0f, 0f);
		when(chunkMapper.selectList(any())).thenReturn(List.of(stale, fresh));

		List<Document> hits = store().similaritySearch(request(5, 0.0));

		assertThat(hits).hasSize(1);
		assertThat(hits.get(0).getText()).isEqualTo("报销期限是 30 天");
	}

	private SearchRequest request(int topK, double threshold) {
		Filter.Expression filter = new FilterExpressionBuilder().eq("owner_id", 42L).build();
		return SearchRequest.builder()
				.query("报销期限")
				.topK(topK)
				.similarityThreshold(threshold)
				.filterExpression(filter)
				.build();
	}

	private KnowledgeChunk chunk(Long id, String content, float... vector) {
		KnowledgeChunk chunk = new KnowledgeChunk();
		chunk.setId(id);
		chunk.setOwnerId(42L);
		chunk.setDocumentId(7L);
		chunk.setChunkIndex(0);
		chunk.setContent(content);
		chunk.setCharStart(0);
		chunk.setCharEnd(content.length());
		chunk.setEmbedding(VectorCodec.encode(vector));
		chunk.setEmbeddingDim(vector.length);
		return chunk;
	}

	/** 可编程的桩嵌入模型：查询向量固定，相似度完全由片段向量决定。 */
	private static final class StubEmbeddingModel implements EmbeddingModel {

		private final float[] output;

		private StubEmbeddingModel(float[] output) {
			this.output = output;
		}

		@Override
		public EmbeddingResponse call(EmbeddingRequest request) {
			return new EmbeddingResponse(List.of(new Embedding(output, 0)));
		}

		@Override
		public float[] embed(Document document) {
			return output;
		}

		@Override
		public int dimensions() {
			return output.length;
		}

	}

}
