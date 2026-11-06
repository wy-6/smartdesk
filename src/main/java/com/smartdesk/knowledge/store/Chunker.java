package com.smartdesk.knowledge.store;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import com.smartdesk.knowledge.config.RagProperties;

/**
 * 文档切片器（Week5 / Day30）。
 *
 * <p><b>切分策略</b>（面试可讲）：
 * <ol>
 *   <li><b>优先在标点处断开</b>：中文的句号、问号、感叹号、换行是天然的语义边界。
 *       如果硬按字数切断，会把"报销期限是 30 天"劈成"报销期限是 30" + "天"，
 *       两片单独看都没意义</li>
 *   <li><b>找不到标点才硬切</b>：连续表格、没有标点的技术文档只能按字数切</li>
 *   <li><b>保留重叠</b>：下一片的起点回退 {@code overlap} 个字符，
 *       防止答案正好落在切割线上被劈成两半</li>
 *   <li><b>尾巴并入上一片</b>：最后剩下的一小段如果不足 {@code minChunkSize}，
 *       直接并进上一片，而不是单独生成一个"一句话碎片"</li>
 * </ol>
 *
 * <p><b>为什么不按 token 切？</b>本项目用字符切，理由是中文场景下一个汉字
 * 大约 0.6~1 个 token，字符数与 token 数数量级一致，做不到精确对齐；
 * 而精确对齐要引入模型分词器（不同模型还不一样），复杂度不划算。
 * 真实项目里会用 tiktoken 或模型自带 tokenizer —— 这是个明确的取舍，
 * 不是偷懒。
 *
 * <p>切片大小与重叠都从配置读（{@link RagProperties}），因为第 5 周要用评测集
 * 对比不同配置，用数据选参数而不是拍脑袋。
 */
@Component
public class Chunker {

	/** 句子结束类标点：优先在这些字符后面断开。 */
	private static final String SENTENCE_ENDINGS = "。！？!?；;\n";

	/** 次级标点：没有句号时，退而求其次。 */
	private static final String CLAUSE_BREAKS = "，,、：:）)】]";

	private final RagProperties properties;

	public Chunker(RagProperties properties) {
		this.properties = properties;
	}

	/**
	 * 把正文切成片段。
	 *
	 * @param text 原始正文
	 * @return 切片列表（顺序即原文顺序）
	 */
	public List<TextChunk> split(String text) {
		List<TextChunk> chunks = new ArrayList<>();
		if (text == null || text.isBlank()) {
			return chunks;
		}

		int size = properties.getChunkSize();
		int overlap = properties.getChunkOverlap();
		int minSize = properties.getMinChunkSize();

		int length = text.length();
		int start = 0;
		int index = 0;

		while (start < length) {
			int hardEnd = Math.min(start + size, length);
			int end = pickBoundary(text, start, hardEnd);

			String content = text.substring(start, end);
			// 尾巴太短：并入上一片，而不是产出一个碎片
			if (!chunks.isEmpty() && content.length() < minSize) {
				TextChunk last = chunks.remove(chunks.size() - 1);
				chunks.add(new TextChunk(last.index(), last.content() + content, last.charStart(), end));
				break;
			}
			chunks.add(new TextChunk(index++, content, start, end));

			if (end >= length) {
				break;
			}
			// 下一片起点回退 overlap，但必须保证严格前进，否则会死循环
			int next = end - overlap;
			start = Math.max(start + 1, next);
		}
		return chunks;
	}

	/**
	 * 在 [start, hardEnd) 里挑一个尽量好的切点。
	 *
	 * <p>规则：先在最后 40% 的区间里找句末标点；找不到就在最后 25% 找分句标点；
	 * 都没有就用硬边界。
	 */
	private int pickBoundary(String text, int start, int hardEnd) {
		int searchFrom = start + Math.max(1, (hardEnd - start) * 3 / 5);
		for (int i = hardEnd - 1; i >= searchFrom; i--) {
			char c = text.charAt(i);
			if (SENTENCE_ENDINGS.indexOf(c) >= 0) {
				return i + 1;
			}
		}
		int clauseFrom = start + Math.max(1, (hardEnd - start) * 3 / 4);
		for (int i = hardEnd - 1; i >= clauseFrom; i--) {
			char c = text.charAt(i);
			if (CLAUSE_BREAKS.indexOf(c) >= 0) {
				return i + 1;
			}
		}
		return hardEnd;
	}

}
