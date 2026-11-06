package com.smartdesk.knowledge.embedding;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Mock Embedding 模型测试（Week5 / Day31）。
 *
 * <p>三条必须成立的性质：<b>确定性</b>（同一段文本永远同一个向量）、
 * <b>归一化</b>（模长为 1，点积即余弦）、<b>相关性可分辨</b>
 * （共享词元的文本比毫不相干的文本更相似）。
 */
class MockEmbeddingModelTest {

	private final MockEmbeddingModel model = new MockEmbeddingModel(256);

	@Test
	@DisplayName("确定性：同一文本两次向量化结果完全一致")
	void isDeterministic() {
		float[] first = model.embedText("报销期限是 30 天");
		float[] second = model.embedText("报销期限是 30 天");

		assertThat(first).containsExactly(second);
	}

	@Test
	@DisplayName("维度符合配置")
	void hasConfiguredDimensions() {
		assertThat(model.embedText("任意文本")).hasSize(256);
		assertThat(model.dimensions()).isEqualTo(256);
	}

	@Test
	@DisplayName("向量已归一化（模长为 1）")
	void isNormalized() {
		float[] vector = model.embedText("公司报销制度说明");

		double sum = 0;
		for (float v : vector) {
			sum += (double) v * v;
		}
		assertThat(Math.sqrt(sum)).isCloseTo(1.0, org.assertj.core.data.Offset.offset(1e-6));
	}

	@Test
	@DisplayName("相关文本相似度高于无关文本")
	void similarTextsScoreHigher() {
		float[] query = model.embedText("报销期限是多久");
		float[] related = model.embedText("员工报销期限为 30 天");
		float[] unrelated = model.embedText("数据库连接池的最大活跃连接数配置");

		double relatedScore = dot(query, related);
		double unrelatedScore = dot(query, unrelated);

		assertThat(relatedScore).isGreaterThan(unrelatedScore);
	}

	@Test
	@DisplayName("空文本返回零向量（不抛异常）")
	void handlesEmptyText() {
		float[] vector = model.embedText("");

		double sum = 0;
		for (float v : vector) {
			sum += (double) v * v;
		}
		assertThat(sum).isZero();
	}

	@Test
	@DisplayName("批量向量化：一次请求多个文本，顺序不乱")
	void embedsBatch() {
		List<float[]> vectors = model.embed(List.of("第一个文本", "第二个文本"));

		assertThat(vectors).hasSize(2);
		assertThat(vectors.get(0)).containsExactly(model.embedText("第一个文本"));
		assertThat(vectors.get(1)).containsExactly(model.embedText("第二个文本"));
	}

	private double dot(float[] a, float[] b) {
		double sum = 0;
		for (int i = 0; i < a.length; i++) {
			sum += (double) a[i] * b[i];
		}
		return sum;
	}

}
