package com.smartdesk.ai.service;

import java.util.function.Consumer;

import com.smartdesk.ai.dto.ChatAnswer;
import com.smartdesk.ai.dto.ClassifyResponse;
import com.smartdesk.ai.dto.SummaryResponse;
import com.smartdesk.ai.dto.TaskDraftResponse;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiException;

/**
 * AI 业务接口（Week3 / Day16）。
 *
 * <p><b>这一层存在的理由：Controller 不碰模型 API。</b>
 * Controller 只做"收参数 → 调 Service → 包响应"，和任务模块完全一致。
 * 好处是：
 * <ul>
 *   <li>模型相关逻辑（Prompt、解析、重试、日志）集中在一处，改起来只改一处</li>
 *   <li>单元测试可以直接调 Service，不需要发 HTTP、不需要真的模型</li>
 *   <li>以后从同步改成异步、从单模型改成多模型路由，Controller 不用动</li>
 * </ul>
 */
public interface AiService {

	/**
	 * 自由问答。
	 *
	 * @param userId 当前登录用户（用于调用记录隔离）
	 * @param message 用户问题
	 * @return 回答 + 调用元信息
	 */
	ChatAnswer chat(Long userId, String message);

	/**
	 * 流式问答（Day20）。
	 *
	 * <p>三个回调分别对应：增量片段、正常结束、失败。
	 * 用回调而不是返回 {@code Flux}，是为了让 Controller 不必依赖 Reactor 类型 ——
	 * Controller 只负责把回调翻译成 SSE 事件。
	 *
	 * @param userId 当前登录用户
	 * @param message 用户问题
	 * @param onDelta 每个增量片段
	 * @param onComplete 正常结束（含完整结果与用量）
	 * @param onError 失败（流已经开始后不能再改 HTTP 状态码，只能发错误事件）
	 */
	void streamChat(Long userId, String message, Consumer<String> onDelta, Consumer<AiChatResult> onComplete,
			Consumer<AiException> onError);

	/**
	 * 摘要。
	 *
	 * @param userId 当前登录用户
	 * @param text 原文
	 * @param maxChars 字数上限
	 * @return 摘要 + 元信息
	 */
	SummaryResponse summarize(Long userId, String text, int maxChars);

	/**
	 * 分类。
	 *
	 * @param userId 当前登录用户
	 * @param text 问题描述
	 * @return 分类代码 + 判断依据 + 元信息
	 */
	ClassifyResponse classify(Long userId, String text);

	/**
	 * 自然语言 → 任务草稿（不落库）。
	 *
	 * @param userId 当前登录用户
	 * @param text 用户描述
	 * @return 校验通过的任务草稿
	 */
	TaskDraftResponse draftTask(Long userId, String text);

}
