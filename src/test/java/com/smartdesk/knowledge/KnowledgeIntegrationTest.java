package com.smartdesk.knowledge;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import com.smartdesk.common.BusinessException;
import com.smartdesk.common.ResourceNotFoundException;
import com.smartdesk.knowledge.domain.SourceType;
import com.smartdesk.knowledge.dto.AskResponse;
import com.smartdesk.knowledge.dto.ChunkResponse;
import com.smartdesk.knowledge.dto.DocumentDetailResponse;
import com.smartdesk.knowledge.dto.DocumentResponse;
import com.smartdesk.knowledge.service.KnowledgeService;
import com.smartdesk.knowledge.service.RagService;
import com.smartdesk.knowledge.service.RetrievalService;
import com.smartdesk.user.entity.AppUser;
import com.smartdesk.user.service.UserService;

/**
 * 知识库集成测试（Week5 / Day30-33，真实 MySQL + Redis）。
 *
 * <p><b>本类最核心的三组用例都是越权测试</b>：
 * <ol>
 *   <li>Bob 检索不到 Alice 的文档</li>
 *   <li>Bob 读不到 Alice 的文档详情</li>
 *   <li>Bob 删不掉 Alice 的文档</li>
 * </ol>
 * 第 1 周定下的规矩是"数据边界只来自登录态"，第 5 周新增了知识库，
 * 这个规矩不能因为功能变复杂就破。<b>新表先写越权测试</b>——
 * 先把"不能越权"钉死，再写功能。
 */
@SpringBootTest
@ActiveProfiles("test")
class KnowledgeIntegrationTest {

	private static final String POLICY = """
			公司报销制度（2026 版）

			第一条 报销期限：员工因公出差产生的费用，须在出差结束后 30 天内提交报销申请。
			逾期超过 60 天的单据不再受理。

			第二条 发票要求：发票抬头必须与公司注册名称完全一致，需提供增值税普通发票或专用发票。

			第三条 审批流程：单笔金额不超过 1000 元的由组长审批；超过 1000 元的须部门经理审批；
			超过 5000 元的还需财务负责人会签。

			第四条 差旅标准：住宿费一线城市每晚上限 600 元，二线城市 450 元，其他城市 350 元。
			凭酒店发票与入住日期一并提交，超标部分由个人承担。

			第五条 交通标准：市内交通实报实销；城际火车二等座可报销，高铁一等座需提前审批。
			市内打车单笔超过 200 元需附说明。

			第六条 培训费用：外部培训需在报名前提交申请，获批后凭发票与成绩单报销，
			每人每年度上限 5000 元。

			第七条 加班餐费：工作日加班至 21:00 之后的餐费可报销，单次上限 60 元，
			需在加班结束后的 5 个工作日内提交。

			第八条 提交方式：所有报销单据统一在 OA 系统"费用报销"模块提交，
			纸质原件按月汇总交财务部归档，保存期限为五年。
			""";

	@Autowired
	private KnowledgeService knowledgeService;

	@Autowired
	private RetrievalService retrievalService;

	@Autowired
	private RagService ragService;

	@Autowired
	private UserService userService;

	private Long aliceId;

	private Long bobId;

	@BeforeEach
	void setUp() {
		aliceId = register("kb-alice-" + System.nanoTime());
		bobId = register("kb-bob-" + System.nanoTime());
	}

	private Long register(String username) {
		AppUser user = userService.register(username, "TestPass123", "测试");
		return user.getId();
	}

	private DocumentResponse importPolicy(Long ownerId) {
		return knowledgeService.create(ownerId, "公司报销制度", "policy.txt", SourceType.TEXT,
				POLICY.getBytes(StandardCharsets.UTF_8));
	}

	// ------------------------------------------------------------------
	// 越权测试（先写）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("越权：Bob 检索不到 Alice 的文档")
	void bobCannotSearchAlicesDocuments() {
		importPolicy(aliceId);

		List<ChunkResponse> hits = retrievalService.search(bobId, "报销期限是多久", 5, null);

		assertThat(hits).isEmpty();
	}

	@Test
	@DisplayName("越权：Bob 读不到 Alice 的文档详情（404 而不是 403）")
	void bobCannotReadAlicesDocument() {
		DocumentResponse aliceDoc = importPolicy(aliceId);

		assertThatThrownBy(() -> knowledgeService.detail(bobId, aliceDoc.id()))
				.isInstanceOf(ResourceNotFoundException.class);
	}

	@Test
	@DisplayName("越权：Bob 删不掉 Alice 的文档")
	void bobCannotDeleteAlicesDocument() {
		DocumentResponse aliceDoc = importPolicy(aliceId);

		assertThatThrownBy(() -> knowledgeService.delete(bobId, aliceDoc.id()))
				.isInstanceOf(ResourceNotFoundException.class);

		// Alice 的文档还在
		assertThat(knowledgeService.detail(aliceId, aliceDoc.id()).document().id()).isEqualTo(aliceDoc.id());
	}

	@Test
	@DisplayName("越权：Bob 的检索不会把 Alice 的文档标题带出来")
	void bobDoesNotSeeAlicesTitles() {
		importPolicy(aliceId);

		List<ChunkResponse> hits = retrievalService.search(bobId, "公司报销制度", 5, null);

		assertThat(hits).noneMatch(hit -> "公司报销制度".equals(hit.documentTitle()));
	}

	// ------------------------------------------------------------------
	// 索引与检索
	// ------------------------------------------------------------------

	@Test
	@DisplayName("导入文档后建立索引：切片数大于 0")
	void createsChunksOnImport() {
		DocumentResponse document = importPolicy(aliceId);

		assertThat(document.chunkCount()).isGreaterThan(0);
		assertThat(document.charCount()).isGreaterThan(0);
		assertThat(document.status()).isEqualTo("INDEXED");
		assertThat(document.duplicate()).isFalse();
	}

	@Test
	@DisplayName("独立检索流程：能命中相关片段，并返回分数与来源")
	void searchReturnsScoredHits() {
		importPolicy(aliceId);

		List<ChunkResponse> hits = retrievalService.search(aliceId, "报销期限是多久", 5, null);

		assertThat(hits).isNotEmpty();
		assertThat(hits.get(0).score()).isGreaterThan(0);
		assertThat(hits.get(0).documentTitle()).isEqualTo("公司报销制度");
		assertThat(hits.get(0).chunkId()).isNotNull();
		assertThat(hits.get(0).chunkIndex()).isNotNull();
	}

	@Test
	@DisplayName("检索结果按相似度从高到低排序")
	void searchResultsAreSorted() {
		importPolicy(aliceId);

		List<ChunkResponse> hits = retrievalService.search(aliceId, "报销期限 30 天", 5, null);

		assertThat(hits).hasSizeGreaterThan(1);
		for (int i = 1; i < hits.size(); i++) {
			assertThat(hits.get(i - 1).score()).isGreaterThanOrEqualTo(hits.get(i).score());
		}
	}

	@Test
	@DisplayName("Top-K 生效：返回数量不超过请求的 K")
	void respectsTopK() {
		importPolicy(aliceId);

		List<ChunkResponse> hits = retrievalService.search(aliceId, "报销", 1, null);

		assertThat(hits).hasSizeLessThanOrEqualTo(1);
	}

	@Test
	@DisplayName("文档详情能看到切片内容（调试切分策略的入口）")
	void detailShowsChunks() {
		DocumentResponse document = importPolicy(aliceId);

		DocumentDetailResponse detail = knowledgeService.detail(aliceId, document.id());

		assertThat(detail.chunks()).isNotEmpty();
		assertThat(detail.chunks()).allSatisfy(chunk -> {
			assertThat(chunk.content()).isNotBlank();
			assertThat(chunk.documentId()).isEqualTo(document.id());
		});
	}

	@Test
	@DisplayName("删除文档会连带清掉它的切片")
	void deleteRemovesChunks() {
		DocumentResponse document = importPolicy(aliceId);

		knowledgeService.delete(aliceId, document.id());

		assertThat(retrievalService.search(aliceId, "报销期限", 5, null)).isEmpty();
	}

	// ------------------------------------------------------------------
	// 重复导入与异常输入（Day35）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("重复导入：识别为重复文档，不重建索引")
	void detectsDuplicateImport() {
		DocumentResponse first = importPolicy(aliceId);
		DocumentResponse second = importPolicy(aliceId);

		assertThat(second.duplicate()).isTrue();
		assertThat(second.id()).isEqualTo(first.id());
		assertThat(second.chunkCount()).isEqualTo(first.chunkCount());
		// 文档列表里只有一份
		assertThat(knowledgeService.list(aliceId, 1, 20)).hasSize(1);
	}

	@Test
	@DisplayName("不同用户导入相同内容不算重复（各自一份）")
	void duplicateIsScopedToOwner() {
		DocumentResponse aliceDoc = importPolicy(aliceId);
		DocumentResponse bobDoc = importPolicy(bobId);

		assertThat(bobDoc.duplicate()).isFalse();
		assertThat(bobDoc.id()).isNotEqualTo(aliceDoc.id());
	}

	@Test
	@DisplayName("空文档被拒绝")
	void rejectsEmptyDocument() {
		assertThatThrownBy(() -> knowledgeService.create(aliceId, "空文档", "empty.txt", SourceType.TEXT,
				new byte[0]))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("空白文档被拒绝（内容全是空格）")
	void rejectsBlankDocument() {
		assertThatThrownBy(() -> knowledgeService.create(aliceId, "空白", "blank.txt", SourceType.TEXT,
				"   \n\t  ".getBytes(StandardCharsets.UTF_8)))
				.isInstanceOf(BusinessException.class);
	}

	@Test
	@DisplayName("不支持的格式被拒绝")
	void rejectsUnsupportedFormat() {
		assertThat(SourceType.fromFileName("policy.docx")).isNull();
		assertThat(SourceType.fromFileName("policy.pdf")).isEqualTo(SourceType.PDF);
		assertThat(SourceType.fromFileName("policy.md")).isEqualTo(SourceType.MARKDOWN);
		assertThat(SourceType.fromFileName("policy.txt")).isEqualTo(SourceType.TEXT);
	}

	// ------------------------------------------------------------------
	// 问答与引用（Day33）
	// ------------------------------------------------------------------

	@Test
	@DisplayName("问答：回答带引用，引用与检索结果一一对应")
	void answerCarriesRealCitations() {
		importPolicy(aliceId);

		AskResponse response = ragService.ask(aliceId, "报销期限是多久", 3);

		assertThat(response.citations()).isNotEmpty();
		assertThat(response.answer()).contains("[1]");
		assertThat(response.unsupportedMarkers()).isEmpty();
		// 引用编号从 1 连续递增，且数量与检索片段数一致
		assertThat(response.citations()).hasSize(response.retrievalCount());
		assertThat(response.citations().get(0).index()).isEqualTo(1);
		assertThat(response.citations().get(0).documentTitle()).isEqualTo("公司报销制度");
	}

	@Test
	@DisplayName("问答：检索不到证据时拒答，且不产生引用")
	void refusesWhenNoEvidence() {
		importPolicy(aliceId);

		// 查询必须与制度正文【零词元重叠】：mock 嵌入是词元哈希模型，
		// 只要共享一个词（比如"公司"）就会产生相似度。
		// 这也正是词法检索的真实特性 —— 换真实语义嵌入后，
		// "语义相近但词面不同"的查询也能命中。
		AskResponse response = ragService.ask(aliceId, "量子纠缠的贝尔不等式如何验证", 3);

		assertThat(response.refused()).isTrue();
		assertThat(response.answer()).contains("无法确认");
		assertThat(response.citations()).isEmpty();
		assertThat(response.retrievalCount()).isZero();
	}

	@Test
	@DisplayName("问答：Bob 问 Alice 的文档内容，检索不到也不该拿到引用")
	void bobCannotAskWithAlicesKnowledge() {
		importPolicy(aliceId);

		AskResponse response = ragService.ask(bobId, "报销期限是多久", 3);

		assertThat(response.citations()).isEmpty();
		assertThat(response.retrievalCount()).isZero();
		assertThat(response.refused()).isTrue();
		assertThat(response.answer()).contains("无法确认");
	}

}
