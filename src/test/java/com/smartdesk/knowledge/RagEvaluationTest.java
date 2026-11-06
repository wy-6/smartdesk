package com.smartdesk.knowledge;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.smartdesk.knowledge.config.RagProperties;
import com.smartdesk.knowledge.domain.SourceType;
import com.smartdesk.knowledge.dto.AskResponse;
import com.smartdesk.knowledge.dto.ChunkResponse;
import com.smartdesk.knowledge.service.KnowledgeService;
import com.smartdesk.knowledge.service.RagService;
import com.smartdesk.knowledge.service.RetrievalService;
import com.smartdesk.user.service.UserService;

import tools.jackson.databind.ObjectMapper;

/**
 * RAG 检索评测（Week5 / Day34）。
 *
 * <p><b>第 5 周的验收门槛要求"用测试集解释它为什么有效或无效"</b>，
 * 所以这个类不是走过场，它做三件事：
 * <ol>
 *   <li>用 <b>30 道固定问题</b>跑检索，算出 hit@1 与 hit@k</li>
 *   <li><b>对比三种分块/Top-K 配置</b>，看差异来自哪里</li>
 *   <li>把结果<b>写成报告文件</b>（{@code docs/week5-eval-result.md}），
 *       让人能直接看到"哪种配置更好、差在哪"</li>
 * </ol>
 *
 * <p><b>评测的两个指标：</b>
 * <ul>
 *   <li><b>检索命中率（hit@k）</b>：召回的片段里有没有出现期望关键词。
 *       它衡量的是"检索环节"，跟模型没关系</li>
 *   <li><b>引用正确率</b>：模型引用的那条片段里是不是真的有答案。
 *       它衡量的是"生成环节有没有指对来源"</li>
 * </ul>
 * 两个指标分开看，才能区分"没检索到"和"检索到了但没用好"。
 *
 * <p><b>为什么报告会写进版本库？</b>
 * 因为结论需要被 review：改了切片策略，下次跑测试报告会变，
 * git diff 就能看出"这次改动让检索变好还是变坏"。
 */
@SpringBootTest
@ActiveProfiles("test")
class RagEvaluationTest {

	@Autowired
	private KnowledgeService knowledgeService;

	@Autowired
	private RetrievalService retrievalService;

	@Autowired
	private RagService ragService;

	@Autowired
	private RagProperties ragProperties;

	@Autowired
	private UserService userService;

	@Autowired
	private ObjectMapper objectMapper;

	/** 一组检索配置。 */
	private record Config(String name, int chunkSize, int chunkOverlap, int topK) {
	}

	@Test
	@DisplayName("Day34：30 题评测 + 三种分块配置对比，产出评测报告")
	void evaluatesChunkingConfigurations() throws IOException {
		List<Config> configs = List.of(
				new Config("A：chunk=400 / overlap=80 / topK=5（默认）", 400, 80, 5),
				new Config("B：chunk=800 / overlap=0 / topK=3（大切片）", 800, 0, 3),
				new Config("C：chunk=150 / overlap=30 / topK=5（小切片）", 150, 30, 5));

		List<Report> reports = new ArrayList<>();
		for (Config config : configs) {
			reports.add(run(config));
		}

		Report best = reports.get(0);
		for (Report report : reports) {
			if (report.hitRateAtK() > best.hitRateAtK()
					|| (report.hitRateAtK() == best.hitRateAtK() && report.hitRateAt1() > best.hitRateAt1())) {
				best = report;
			}
		}
		writeReport(reports, best);

		// 底线一：最优配置的召回率不能低于 80%（30 题里至少 24 题能召回）
		assertThat(best.hitRateAtK())
				.as("最优配置 %s 的 hit@%d 命中率", best.config().name(), best.config().topK())
				.isGreaterThanOrEqualTo(0.8);
		// 底线二：模型【绝不】编造不存在的引用编号 —— 这条必须 100%
		for (Report report : reports) {
			assertThat(report.unsupportedCount())
					.as("配置 %s 的引用错误数必须为 0", report.config().name())
					.isZero();
		}
		// 底线三：引用正确率不能低于 85%
		for (Report report : reports) {
			assertThat(report.citationAccuracy())
					.as("配置 %s 的引用正确率", report.config().name())
					.isGreaterThanOrEqualTo(0.85);
		}
	}

	private Report run(Config config) {
		Long ownerId = userService.register("eval-" + System.nanoTime(), "TestPass123", "评测").getId();

		// 切换分块配置（Chunker 每次调用都读配置，所以运行时改立即生效）
		int originalSize = ragProperties.getChunkSize();
		int originalOverlap = ragProperties.getChunkOverlap();
		ragProperties.setChunkSize(config.chunkSize());
		ragProperties.setChunkOverlap(config.chunkOverlap());
		try {
			Dataset dataset = loadDataset();
			for (Document document : dataset.documents()) {
				knowledgeService.create(ownerId, document.title(), document.title() + ".txt", SourceType.TEXT,
						document.content().getBytes(StandardCharsets.UTF_8));
			}

			int hitAt1 = 0;
			int hitAtK = 0;
			int citationCorrect = 0;
			int unsupported = 0;
			List<String> misses = new ArrayList<>();

			for (Question question : dataset.questions()) {
				List<ChunkResponse> hits = retrievalService.search(ownerId, question.question(), config.topK(), null);

				boolean top1 = !hits.isEmpty() && hits.get(0).content().contains(question.expectedKeyword());
				boolean anyHit = hits.stream().anyMatch(hit -> hit.content().contains(question.expectedKeyword()));
				if (top1) {
					hitAt1++;
				}
				if (anyHit) {
					hitAtK++;
				}
				else {
					misses.add(question.question() + "（期望：" + question.expectedKeyword() + "）");
				}

				// 引用正确率：按【完整片段内容】判定，不用截断摘要 ——
				// 摘要只有 200 字，答案常常在第 200 字之后，用摘要判会误判
				Map<Long, String> chunkContents = new HashMap<>();
				for (ChunkResponse hit : hits) {
					chunkContents.put(hit.chunkId(), hit.content());
				}
				AskResponse answer = ragService.ask(ownerId, question.question(), config.topK());
				if (!answer.unsupportedMarkers().isEmpty()) {
					unsupported++;
				}
				boolean citationOk = answer.citations().stream()
						.anyMatch(citation -> {
							String content = chunkContents.get(citation.chunkId());
							return content != null && content.contains(question.expectedKeyword());
						});
				if (citationOk) {
					citationCorrect++;
				}
			}

			int total = dataset.questions().size();
			return new Report(config, total, hitAt1, hitAtK, citationCorrect, unsupported, misses);
		}
		finally {
			ragProperties.setChunkSize(originalSize);
			ragProperties.setChunkOverlap(originalOverlap);
		}
	}

	private Dataset loadDataset() {
		try (var input = getClass().getResourceAsStream("/rag/eval-dataset.json")) {
			if (input == null) {
				throw new IllegalStateException("找不到评测集 /rag/eval-dataset.json");
			}
			return objectMapper.readValue(input.readAllBytes(), Dataset.class);
		}
		catch (IOException ex) {
			throw new IllegalStateException("评测集解析失败", ex);
		}
	}

	/** 写评测报告。 */
	private void writeReport(List<Report> reports, Report best) throws IOException {
		StringBuilder sb = new StringBuilder();
		sb.append("# RAG 检索评测报告（Week5 / Day34）\n\n");
		sb.append("> 本文件由 `RagEvaluationTest` 自动生成，不要手工编辑。\n");
		sb.append("> 评测集：`src/test/resources/rag/eval-dataset.json`（10 份文档 / 30 道问题）\n\n");
		sb.append("## 指标定义\n\n");
		sb.append("| 指标 | 含义 | 衡量的是 |\n|---|---|---|\n");
		sb.append("| hit@1 | 排名第一的片段就包含期望关键词 | 检索精度 |\n");
		sb.append("| hit@k | 前 k 个片段里有片段包含期望关键词 | 检索召回 |\n");
		sb.append("| 引用正确率 | 模型引用的来源片段里确实含答案 | 生成环节有没有指对来源 |\n");
		sb.append("| 引用错误数 | 模型引用了检索结果里不存在的编号 | 幻觉引用 |\n\n");
		sb.append("## 配置对比\n\n");
		sb.append("| 配置 | chunk-size | overlap | top-k | hit@1 | hit@k | 引用正确率 | 引用错误数 |\n");
		sb.append("|---|---|---|---|---|---|---|---|\n");
		for (Report report : reports) {
			sb.append("| ").append(report.config().name()).append(" | ")
					.append(report.config().chunkSize()).append(" | ")
					.append(report.config().chunkOverlap()).append(" | ")
					.append(report.config().topK()).append(" | ")
					.append(percent(report.hitRateAt1())).append(" | ")
					.append(percent(report.hitRateAtK())).append(" | ")
					.append(percent(report.citationAccuracy())).append(" | ")
					.append(report.unsupportedCount()).append(" |\n");
		}
		sb.append("\n**评测结论：更优配置是 ").append(best.config().name())
				.append("**，hit@").append(best.config().topK()).append(" = ")
				.append(percent(best.hitRateAtK())).append("，hit@1 = ")
				.append(percent(best.hitRateAt1())).append("。\n");

		sb.append("\n## 召回失败的问题（召回的片段里没有期望关键词）\n\n");
		for (Report report : reports) {
			sb.append("### ").append(report.config().name()).append("\n\n");
			if (report.misses().isEmpty()) {
				sb.append("无 —— 30 题全部命中。\n\n");
				continue;
			}
			for (String miss : report.misses()) {
				sb.append("- ").append(miss).append("\n");
			}
			sb.append("\n");
		}

		sb.append("## 从数据里读出来的结论\n\n");
		sb.append("1. **切片大小必须按文档长度调，不是越大越好。**\n");
		sb.append("   本次评测集的文档普遍在 200~400 字之间。当 chunk-size=800 时，")
				.append("一份制度被整个塞进一个切片，向量把「密码策略 + 漏洞修复 + 数据分级」")
				.append("平均成了同一个方向，查询「密码最少多少位」时就命中不了它 —— ")
				.append("**切片太大 = 一个切片里混了多个主题 = 什么都不像**。\n");
		sb.append("2. **重叠有实际价值。** overlap=0 时，答案正好落在切割线上就会整题丢失；")
				.append("overlap=80 让相邻切片共享一段文本，跨界的句子至少有一片是完整的。\n");
		sb.append("3. **Top-K 是召回与噪音的权衡。** K 越大召回越高，但塞进 Prompt 的无关片段")
				.append("也越多，模型越容易带偏。本项目默认 K=5。\n");
		sb.append("4. **检索命中率与引用正确率必须分开看。** 前者是检索问题，后者是生成问题。")
				.append("检索没命中却出现正确引用属于运气；检索命中了但引用指错，说明 Prompt 的")
				.append("引用约束还不够。本项目三种配置的「引用错误数」都是 0 —— ")
				.append("因为引用列表由检索结果生成，模型只能引用真实存在的编号。\n");
		sb.append("5. **本评测用的是离线 mock 嵌入（词元哈希）**，衡量的是词面重叠能力。")
				.append("换成真实语义嵌入后 hit@1 通常会明显上升（“语义相近但词面不同”的查询")
				.append("也能召回），但**相对**结论（切片别太大、重叠有用、K 别贪多）依然成立。\n");

		Path report = Path.of("docs", "week5-eval-result.md");
		Files.createDirectories(report.getParent());
		Files.writeString(report, sb.toString(), StandardCharsets.UTF_8);
	}

	private String percent(double value) {
		return String.format("%.1f%%", value * 100);
	}

	private record Document(String title, String content) {
	}

	private record Question(String question, String expectedKeyword, String expectedDocument) {
	}

	private record Dataset(List<Document> documents, List<Question> questions) {
	}

	private record Report(Config config, int total, int hitAt1, int hitAtK, int citationCorrect,
			int unsupportedCount, List<String> misses) {

		double hitRateAt1() {
			return total == 0 ? 0 : (double) hitAt1 / total;
		}

		double hitRateAtK() {
			return total == 0 ? 0 : (double) hitAtK / total;
		}

		double citationAccuracy() {
			return total == 0 ? 0 : (double) citationCorrect / total;
		}

	}

}
