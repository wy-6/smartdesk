package com.smartdesk.knowledge.controller;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.smartdesk.common.ApiResponse;
import com.smartdesk.common.BusinessException;
import com.smartdesk.knowledge.domain.SourceType;
import com.smartdesk.knowledge.dto.AskRequest;
import com.smartdesk.knowledge.dto.AskResponse;
import com.smartdesk.knowledge.dto.DocumentCreateRequest;
import com.smartdesk.knowledge.dto.DocumentDetailResponse;
import com.smartdesk.knowledge.dto.DocumentResponse;
import com.smartdesk.knowledge.dto.SearchRequest;
import com.smartdesk.knowledge.dto.SearchResponse;
import com.smartdesk.knowledge.service.KnowledgeService;
import com.smartdesk.knowledge.service.RagService;
import com.smartdesk.knowledge.service.RetrievalService;
import com.smartdesk.security.CurrentUser;

import jakarta.validation.Valid;

/**
 * 知识库接口（Week5 / Day30-33）。
 *
 * <p><b>七个接口分三组，这个分组本身就是设计：</b>
 * <pre>
 * 【索引】
 * POST   /api/knowledge/documents            导入文档（文本）
 * POST   /api/knowledge/documents/upload     导入文档（文件：txt/md/pdf）
 * GET    /api/knowledge/documents            我的文档列表
 * GET    /api/knowledge/documents/{id}       文档详情（含切片）
 * DELETE /api/knowledge/documents/{id}       删除文档
 *
 * 【检索】← 必须独立存在，不能只有问答接口
 * POST   /api/knowledge/search               检索片段（带来源与分数）
 *
 * 【问答】
 * POST   /api/knowledge/ask                  带引用的回答
 * </pre>
 *
 * <p>第 5 周的验收门槛明确写着：<b>"不接受只有一个聊天接口、没有独立检索流程、
 * 没有来源引用的实现"</b>。所以 {@code /search} 是独立接口，
 * 不经过大模型就能看到检索到了什么。
 *
 * <p><b>ownerId 一律来自 JWT</b>：本控制器里没有任何地方接收客户端传的
 * 用户标识。数据边界只来自登录态 —— 这是第 1 周定下的规矩，第 5 周没破。
 */
@RestController
@RequestMapping("/api/knowledge")
public class KnowledgeController {

	private final KnowledgeService knowledgeService;

	private final RetrievalService retrievalService;

	private final RagService ragService;

	public KnowledgeController(KnowledgeService knowledgeService, RetrievalService retrievalService,
			RagService ragService) {
		this.knowledgeService = knowledgeService;
		this.retrievalService = retrievalService;
		this.ragService = ragService;
	}

	// ------------------------------------------------------------------
	// 索引
	// ------------------------------------------------------------------

	/**
	 * 导入文档（直接粘贴文本）。
	 */
	@PostMapping("/documents")
	public ResponseEntity<ApiResponse<DocumentResponse>> create(@Valid @RequestBody DocumentCreateRequest request) {
		Long userId = requireCurrentUserId();
		if (request.content() == null || request.content().isBlank()) {
			throw new BusinessException("KNOWLEDGE_DOCUMENT_EMPTY", "content 不能为空");
		}
		SourceType sourceType = parseSourceType(request.sourceType());
		byte[] content = request.content().getBytes(StandardCharsets.UTF_8);
		DocumentResponse response = knowledgeService.create(userId, request.title(), null, sourceType, content);
		return respond(response);
	}

	/**
	 * 导入文档（上传文件）。
	 *
	 * <p>只接受 txt / md / pdf。其它格式直接拒绝并说清楚原因，
	 * 而不是"解析失败"这种没信息量的错误。
	 */
	@PostMapping(value = "/documents/upload", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
	public ResponseEntity<ApiResponse<DocumentResponse>> upload(
			@RequestParam("file") MultipartFile file,
			@RequestParam(value = "title", required = false) String title) {
		Long userId = requireCurrentUserId();
		if (file == null || file.isEmpty()) {
			throw new BusinessException("KNOWLEDGE_DOCUMENT_EMPTY", "上传的文件为空");
		}
		String fileName = file.getOriginalFilename();
		SourceType sourceType = SourceType.fromFileName(fileName);
		if (sourceType == null) {
			throw new BusinessException("KNOWLEDGE_UNSUPPORTED_FORMAT",
					"不支持的文件格式，仅支持 .txt / .md / .pdf，收到：" + fileName);
		}
		byte[] content;
		try {
			content = file.getBytes();
		}
		catch (IOException ex) {
			throw new BusinessException("KNOWLEDGE_PARSE_FAILED", "读取上传文件失败", ex);
		}
		String docTitle = (title == null || title.isBlank())
				? (fileName == null ? "未命名文档" : fileName)
				: title;
		DocumentResponse response = knowledgeService.create(userId, docTitle, fileName, sourceType, content);
		return respond(response);
	}

	/**
	 * 我的文档列表。
	 */
	@GetMapping("/documents")
	public ResponseEntity<ApiResponse<List<DocumentResponse>>> list(
			@RequestParam(defaultValue = "1") int page,
			@RequestParam(defaultValue = "20") int pageSize) {
		Long userId = requireCurrentUserId();
		List<DocumentResponse> documents = knowledgeService.list(userId, Math.max(1, page),
				Math.min(100, Math.max(1, pageSize)));
		return ResponseEntity.ok(ApiResponse.success(documents));
	}

	/**
	 * 文档详情（含切片）。
	 */
	@GetMapping("/documents/{id}")
	public ResponseEntity<ApiResponse<DocumentDetailResponse>> detail(@PathVariable Long id) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(knowledgeService.detail(userId, id)));
	}

	/**
	 * 删除文档。
	 */
	@DeleteMapping("/documents/{id}")
	public ResponseEntity<ApiResponse<Void>> delete(@PathVariable Long id) {
		Long userId = requireCurrentUserId();
		knowledgeService.delete(userId, id);
		return ResponseEntity.ok(ApiResponse.success(null));
	}

	// ------------------------------------------------------------------
	// 检索（独立流程，不经过模型）
	// ------------------------------------------------------------------

	/**
	 * 知识检索。
	 */
	@PostMapping("/search")
	public ResponseEntity<ApiResponse<SearchResponse>> search(@Valid @RequestBody SearchRequest request) {
		Long userId = requireCurrentUserId();
		RetrievalService.SearchResult result = retrievalService.searchDetailed(userId, request.query(),
				request.topK(), request.threshold());
		SearchResponse response = new SearchResponse(request.query(),
				result.topK(), result.threshold(), result.hits().size(), result.hits());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

	// ------------------------------------------------------------------
	// 问答
	// ------------------------------------------------------------------

	/**
	 * 知识库问答（带来源引用）。
	 */
	@PostMapping("/ask")
	public ResponseEntity<ApiResponse<AskResponse>> ask(@Valid @RequestBody AskRequest request) {
		Long userId = requireCurrentUserId();
		return ResponseEntity.ok(ApiResponse.success(ragService.ask(userId, request.question(), request.topK())));
	}

	// ------------------------------------------------------------------
	// 内部方法
	// ------------------------------------------------------------------

	/**
	 * 新建返回 201；命中重复文档返回 200（幂等语义：重复导入不算错误）。
	 */
	private ResponseEntity<ApiResponse<DocumentResponse>> respond(DocumentResponse response) {
		if (response.duplicate()) {
			return ResponseEntity.ok(ApiResponse.success(response));
		}
		return ResponseEntity.status(HttpStatus.CREATED).body(ApiResponse.success(response));
	}

	private SourceType parseSourceType(String value) {
		if (value == null || value.isBlank()) {
			return SourceType.TEXT;
		}
		try {
			return SourceType.valueOf(value.trim().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			throw new BusinessException("KNOWLEDGE_UNSUPPORTED_FORMAT",
					"不支持的 sourceType：" + value + "（支持 TEXT / MARKDOWN / PDF）");
		}
	}

	private Long requireCurrentUserId() {
		Long userId = CurrentUser.getUserId();
		if (userId == null) {
			throw new BusinessException("UNAUTHORIZED", "未登录");
		}
		return userId;
	}

}
