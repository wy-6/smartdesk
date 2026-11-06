package com.smartdesk.knowledge.store;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.smartdesk.knowledge.config.RagProperties;

/**
 * 切片器测试（Week5 / Day30）。
 *
 * <p>切分是 RAG 的第一道关口：切坏了，后面检索再准也没用。
 * 所以这里把"可配置"这件事测实 —— 不同配置必须真的产生不同的切法。
 */
class ChunkerTest {

	private Chunker chunker(int size, int overlap, int minSize) {
		RagProperties properties = new RagProperties();
		properties.setChunkSize(size);
		properties.setChunkOverlap(overlap);
		properties.setMinChunkSize(minSize);
		return new Chunker(properties);
	}

	@Test
	@DisplayName("切片长度受 chunk-size 约束，不会超过配置上限太多")
	void respectsChunkSize() {
		String text = "报销制度第一条：员工因公出差产生的交通费用，可在出差结束后 30 天内提交报销申请。"
				+ "第二条：单次报销金额超过 5000 元的，需要部门经理与财务负责人双重审批。"
				+ "第三条：发票必须为增值税普通发票或专用发票，抬头须与公司注册名称完全一致。";

		List<TextChunk> chunks = chunker(60, 10, 10).split(text);

		assertThat(chunks).isNotEmpty();
		// 每个切片长度不超过 chunk-size（标点回退可能略超一点，但不应超出一个句子）
		assertThat(chunks).allSatisfy(chunk -> assertThat(chunk.length()).isLessThanOrEqualTo(80));
	}

	@Test
	@DisplayName("重叠生效：相邻切片之间有重复内容")
	void appliesOverlap() {
		String text = "第一句内容用于测试重叠。第二句内容也用于测试重叠。第三句还是测试重叠。第四句收尾。";

		List<TextChunk> chunks = chunker(40, 15, 10).split(text);

		assertThat(chunks.size()).isGreaterThan(1);
		// 相邻两片的重叠长度 = 前片结束位置 - 后片开始位置
		int overlap = chunks.get(0).charEnd() - chunks.get(1).charStart();
		assertThat(overlap).isPositive();
	}

	@Test
	@DisplayName("优先在句末标点处断开，不会把句子劈成两半")
	void prefersSentenceBoundary() {
		String text = "第一句话讲的是报销期限是三十天。第二句话讲的是发票抬头怎么写。第三句话讲的是审批流程怎么走。";

		List<TextChunk> chunks = chunker(45, 0, 10).split(text);

		assertThat(chunks).allSatisfy(chunk -> {
			String content = chunk.content().strip();
			// 切片结尾应该落在句末标点上（除非是最后一片）
			assertThat(content.endsWith("。") || content.endsWith("？") || content.endsWith("！")
					|| chunk.index() == chunks.size() - 1).isTrue();
		});
	}

	@Test
	@DisplayName("太短的尾巴并入上一片，不产生一句话碎片")
	void mergesShortTail() {
		String text = "这是一段足够长的正文内容用来触发切片逻辑，需要超过最小切片长度才行。"
				+ "。尾巴";

		List<TextChunk> chunks = chunker(45, 0, 20).split(text);

		assertThat(chunks).hasSize(1);
		assertThat(chunks.get(0).content()).contains("尾巴");
	}

	@Test
	@DisplayName("空文本与空白文本返回空切片列表")
	void handlesEmptyText() {
		Chunker chunker = chunker(100, 10, 10);

		assertThat(chunker.split(null)).isEmpty();
		assertThat(chunker.split("")).isEmpty();
		assertThat(chunker.split("   \n  ")).isEmpty();
	}

	@Test
	@DisplayName("切片偏移量能还原原文位置")
	void keepsOffsets() {
		String text = "第一段内容。第二段内容。第三段内容。";

		List<TextChunk> chunks = chunker(30, 0, 5).split(text);

		assertThat(chunks).allSatisfy(chunk -> {
			assertThat(text.substring(chunk.charStart(), chunk.charEnd())).isEqualTo(chunk.content());
		});
	}

}
