package com.smartdesk.knowledge.embedding;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.springframework.ai.document.Document;
import org.springframework.ai.embedding.Embedding;
import org.springframework.ai.embedding.EmbeddingModel;
import org.springframework.ai.embedding.EmbeddingRequest;
import org.springframework.ai.embedding.EmbeddingResponse;

/**
 * 离线确定性 Embedding 模型（Week5 / Day31）。
 *
 * <p><b>它是什么：</b>把文本切成词元（英文按单词、中文按二元组），
 * 用<b>带符号的哈希</b>把每个词元投到固定维度的向量上，再做 L2 归一化。
 *
 * <p><b>它不是什么：</b>它<b>不理解语义</b>。
 * "报销期限是多久" 和 "报销期限是几天" 能匹配上（共享词元），
 * 但 "多少钱能报" 和 "报销额度" 这种同义不同词的情况匹配不上 ——
 * 真正的语义 embedding（text-embedding-v3、bge）能，因为它们在预训练里
 * 学过词与词的关系。
 *
 * <p><b>那为什么还要它？</b>
 * <ul>
 *   <li><b>离线可跑、可重复</b>：CI 和全部自动化测试都不需要 Key、不花钱、
 *       每次结果完全一样 —— 这是本项目所有模块一贯的要求</li>
 *   <li><b>工程链路能被真实验证</b>：切片 → 向量化 → 存储 → 检索 → Top-K →
 *       阈值 → 引用，这条链路的每一环都被 mock embedding 真实跑过</li>
 *   <li><b>可以量化对比</b>：第 5 周评测要用它跑 30 道题的 hit@k，
 *       因为确定性才能复现</li>
 * </ul>
 * 换成真实 embedding 只需要改一个配置项，检索代码一行不改。
 *
 * <p><b>带符号哈希（signed hashing）是什么：</b>
 * 哈希到同一个桶的两个不同词元，如果用同号会互相"加分"，
 * 造成相似度虚高。给每个词元随机定一个正负号（这里用哈希的另一位决定），
 * 碰撞产生的正负贡献相互抵消，统计上更接近真实相似度。
 * 这是推荐系统里 Locality Sensitive Hashing 的标准技巧。
 */
public class MockEmbeddingModel implements EmbeddingModel {

	private final int dimensions;

	public MockEmbeddingModel(int dimensions) {
		if (dimensions <= 0) {
			throw new IllegalArgumentException("embedding 维度必须大于 0");
		}
		this.dimensions = dimensions;
	}

	@Override
	public EmbeddingResponse call(EmbeddingRequest request) {
		List<Embedding> results = new ArrayList<>();
		List<String> texts = request.getInstructions();
		for (int i = 0; i < texts.size(); i++) {
			results.add(new Embedding(embedText(texts.get(i)), i));
		}
		return new EmbeddingResponse(results);
	}

	@Override
	public float[] embed(Document document) {
		return embedText(document.getText());
	}

	@Override
	public int dimensions() {
		return dimensions;
	}

	/**
	 * 把文本转成归一化向量。
	 *
	 * @param text 输入文本
	 * @return 长度为 {@link #dimensions} 的单位向量
	 */
	public float[] embedText(String text) {
		float[] vector = new float[dimensions];
		if (text == null || text.isBlank()) {
			return vector;
		}
		Map<String, Integer> termFrequency = tokenize(text);
		for (Map.Entry<String, Integer> entry : termFrequency.entrySet()) {
			String token = entry.getKey();
			int tf = entry.getValue();
			int hash = token.hashCode();
			int bucket = Math.floorMod(hash, dimensions);
			// 符号由哈希的另一位决定：碰撞时正负抵消，避免相似度虚高
			float sign = ((hash >>> 16) & 1) == 0 ? 1f : -1f;
			// 次线性词频：同一个词出现 10 次不应该有 10 倍权重
			float weight = (float) (1.0 + Math.log(tf));
			vector[bucket] += sign * weight;
		}
		return normalize(vector);
	}

	/**
	 * 词元切分：英文/数字按单词，中文按二元组（bigram）。
	 *
	 * <p>为什么中文用二元组而不是单字？单字太粗，"报销"和"报销单"会共享"报"，
	 * 几乎所有中文词都共享常用字，相似度全糊在一起。二元组粒度刚好，
	 * 又不需要引入分词器（jieba 之类），对检索效果足够。
	 */
	private Map<String, Integer> tokenize(String text) {
		Map<String, Integer> counts = new HashMap<>();
		String lower = text.toLowerCase();
		StringBuilder ascii = new StringBuilder();
		StringBuilder cjk = new StringBuilder();

		for (int i = 0; i < lower.length(); i++) {
			char c = lower.charAt(i);
			if (isCjk(c)) {
				flushAscii(ascii, counts);
				cjk.append(c);
			}
			else if (Character.isLetterOrDigit(c)) {
				flushCjk(cjk, counts);
				ascii.append(c);
			}
			else {
				// 标点/空白是天然边界
				flushAscii(ascii, counts);
				flushCjk(cjk, counts);
			}
		}
		flushAscii(ascii, counts);
		flushCjk(cjk, counts);
		return counts;
	}

	private void flushAscii(StringBuilder buffer, Map<String, Integer> counts) {
		if (buffer.length() > 0) {
			counts.merge(buffer.toString(), 1, Integer::sum);
			buffer.setLength(0);
		}
	}

	private void flushCjk(StringBuilder buffer, Map<String, Integer> counts) {
		if (buffer.length() == 1) {
			// 单个汉字：保留（短查询也可能是单字）
			counts.merge(buffer.toString(), 1, Integer::sum);
			buffer.setLength(0);
			return;
		}
		for (int i = 0; i + 1 < buffer.length(); i++) {
			counts.merge(buffer.substring(i, i + 2), 1, Integer::sum);
		}
		buffer.setLength(0);
	}

	private boolean isCjk(char c) {
		return (c >= '一' && c <= '鿿') || (c >= '㐀' && c <= '䶿');
	}

	/** L2 归一化：归一化之后，余弦相似度就等于点积。 */
	private float[] normalize(float[] vector) {
		double sum = 0;
		for (float v : vector) {
			sum += v * v;
		}
		double norm = Math.sqrt(sum);
		if (norm > 0) {
			for (int i = 0; i < vector.length; i++) {
				vector[i] = (float) (vector[i] / norm);
			}
		}
		return vector;
	}

}
