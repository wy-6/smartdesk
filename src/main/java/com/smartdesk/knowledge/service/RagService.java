package com.smartdesk.knowledge.service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.springframework.ai.chat.client.ChatClient;
import org.springframework.ai.chat.messages.SystemMessage;
import org.springframework.ai.chat.messages.UserMessage;
import org.springframework.ai.chat.metadata.ChatResponseMetadata;
import org.springframework.ai.chat.metadata.Usage;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;
import org.springframework.stereotype.Service;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.dto.AiMeta;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.model.AiScene;
import com.smartdesk.ai.service.AiCallLogService;
import com.smartdesk.ai.service.AiInvoker;
import com.smartdesk.ai.service.AiMetrics;
import com.smartdesk.knowledge.dto.AskResponse;
import com.smartdesk.knowledge.dto.ChunkResponse;
import com.smartdesk.knowledge.dto.Citation;
import com.smartdesk.knowledge.prompt.RagPromptTemplates;

/**
 * 知识库问答服务（Week5 / Day33）。
 *
 * <p><b>三条铁律：</b>
 * <ol>
 *   <li><b>检索不到就不调模型</b>：没有证据时直接返回拒答话术。
 *       既省钱（一次模型调用不便宜），又杜绝了"模型自由发挥"的幻觉</li>
 *   <li><b>引用来自检索结果，不来自模型</b>：citations 列表是我们自己从检索结果
 *       生成的，编号和资料区块严格对齐。模型说"依据 [1]"时，[1] 一定真实存在</li>
 *   <li><b>模型的引用要核对</b>：如果模型引用了 [99]（检索结果里根本没有），
 *       会被记录到 {@code unsupportedMarkers} —— 这就是"引用错误"，
 *       第 5 周评测集用它来量化生成环节的质量</li>
 * </ol>
 *
 * <p><b>面试可讲：</b>RAG 的错误要分清是<b>检索问题</b>还是<b>生成问题</b>：
 * <ul>
 *   <li>检索问题：答案其实在资料里，但没被召回 → 调切片策略、调 Top-K、换 embedding</li>
 *   <li>生成问题：资料召回了，模型却没用/用错了 → 调 Prompt、加约束、检查引用</li>
 * </ul>
 * 本类把 {@code retrievalCount}、{@code citations}、{@code unsupportedMarkers}
 * 一起返回，就是为了让人能一眼分清是哪种问题。
 */
@Service
public class RagService {

	/** 引用标记：[1]、[2]。 */
	private static final Pattern CITATION_MARKER = Pattern.compile("\\[(\\d+)]");

	/** 模型拒答时用的关键词。 */
	private static final List<String> REFUSAL_MARKERS = List.of("无法确认", "没有相关", "未找到相关", "资料中没有");

	private final RetrievalService retrievalService;

	private final ChatClient chatClient;

	private final AiInvoker invoker;

	private final AiCallLogService callLogService;

	private final AiMetrics metrics;

	private final AiProperties properties;

	public RagService(RetrievalService retrievalService, ChatClient chatClient, AiInvoker invoker,
			AiCallLogService callLogService, AiMetrics metrics, AiProperties properties) {
		this.retrievalService = retrievalService;
		this.chatClient = chatClient;
		this.invoker = invoker;
		this.callLogService = callLogService;
		this.metrics = metrics;
		this.properties = properties;
	}

	/**
	 * 知识库问答。
	 *
	 * @param ownerId 归属人（来自 JWT）
	 * @param question 问题
	 * @param topK 召回数量，null 用配置默认值
	 */
	public AskResponse ask(Long ownerId, String question, Integer topK) {
		List<ChunkResponse> hits = retrievalService.search(ownerId, question, topK, null);

		if (hits.isEmpty()) {
			// ★ 没有证据就不调模型：省钱 + 防幻觉
			return new AskResponse(question, RagPromptTemplates.REFUSAL_ANSWER, true, List.of(), 0,
					List.of(), null);
		}

		String traceId = UUID.randomUUID().toString().replace("-", "");
		long startedAt = System.currentTimeMillis();
		try {
			Prompt prompt = new Prompt(
					new SystemMessage(RagPromptTemplates.ragSystem()),
					new UserMessage(RagPromptTemplates.userMessage(hits, question)));
			AiChatResult result = callModel(prompt, traceId);
			String answer = result.content().strip();
			if (answer.isEmpty()) {
				throw new AiException(AiErrorCode.INVALID_OUTPUT, "模型返回了空回答");
			}

			List<Citation> citations = buildCitations(hits);
			List<Integer> unsupported = findUnsupportedMarkers(answer, hits.size());
			boolean refused = isRefusal(answer);

			metrics.recordSuccess(AiScene.RAG, result);
			callLogService.recordSuccess(ownerId, AiScene.RAG, result);

			return new AskResponse(question, answer, refused, citations, hits.size(), unsupported,
					AiMeta.from(result));
		}
		catch (AiException ex) {
			long latency = System.currentTimeMillis() - startedAt;
			metrics.recordFailure(AiScene.RAG, ex.getCode(), latency);
			callLogService.recordFailure(ownerId, AiScene.RAG, traceId, ex, latency);
			throw ex;
		}
	}

	/**
	 * 引用列表：编号从 1 开始，与 Prompt 里的资料编号一一对应。
	 */
	private List<Citation> buildCitations(List<ChunkResponse> hits) {
		List<Citation> citations = new ArrayList<>();
		for (int i = 0; i < hits.size(); i++) {
			ChunkResponse hit = hits.get(i);
			citations.add(new Citation(i + 1, hit.chunkId(), hit.documentId(), hit.documentTitle(),
					hit.chunkIndex(), hit.pageNo(), hit.snippet(), hit.score()));
		}
		return citations;
	}

	/**
	 * 找出模型引用了、但检索结果里不存在的编号。
	 *
	 * <p>这就是"引用错误"的定义：不是"模型答错了"，而是"模型指向了一个不存在的来源"。
	 * 用户点开 [99] 会发现什么都没有 —— 这种错误比答错更严重，因为它伪装成有依据。
	 */
	private List<Integer> findUnsupportedMarkers(String answer, int validCount) {
		Set<Integer> unsupported = new LinkedHashSet<>();
		Matcher matcher = CITATION_MARKER.matcher(answer);
		while (matcher.find()) {
			int marker = Integer.parseInt(matcher.group(1));
			if (marker < 1 || marker > validCount) {
				unsupported.add(marker);
			}
		}
		return new ArrayList<>(unsupported);
	}

	/**
	 * 判断模型是不是拒答了。
	 *
	 * <p>注意：<b>模型拒答时同样要返回 citations</b> —— 用户需要知道
	 * "系统查了这些资料，但里面确实没有答案"，而不是"系统坏了"。
	 */
	private boolean isRefusal(String answer) {
		return REFUSAL_MARKERS.stream().anyMatch(answer::contains);
	}

	private AiChatResult callModel(Prompt prompt, String traceId) {
		long startedAt = System.currentTimeMillis();
		AiInvoker.Attempt<ChatResponse> attempt = invoker.execute(AiScene.RAG,
				() -> chatClient.prompt(prompt).call().chatResponse());
		long latency = System.currentTimeMillis() - startedAt;
		ChatResponse response = attempt.value();
		ChatResponseMetadata metadata = response == null ? null : response.getMetadata();
		Usage usage = metadata == null ? null : metadata.getUsage();
		int promptTokens = usage == null || usage.getPromptTokens() == null ? 0 : usage.getPromptTokens();
		int completionTokens = usage == null || usage.getCompletionTokens() == null ? 0
				: usage.getCompletionTokens();
		String model = metadata == null || metadata.getModel() == null ? properties.getModel() : metadata.getModel();
		return new AiChatResult(extractText(response), AiScene.RAG.getCode(), provider(), model, promptTokens,
				completionTokens, latency, attempt.attempts(), traceId);
	}

	private static String extractText(ChatResponse response) {
		if (response == null || response.getResult() == null || response.getResult().getOutput() == null) {
			return "";
		}
		String text = response.getResult().getOutput().getText();
		return text == null ? "" : text;
	}

	private String provider() {
		return properties.getProvider() == null ? "unknown" : properties.getProvider().toLowerCase();
	}

}
