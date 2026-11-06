package com.smartdesk.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.client.ChatClient;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.dto.ChatAnswer;
import com.smartdesk.ai.dto.ClassifyResponse;
import com.smartdesk.ai.dto.SummaryResponse;
import com.smartdesk.ai.dto.TaskDraftResponse;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.model.AiScene;
import com.smartdesk.ai.provider.MockChatModel;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tools.jackson.databind.ObjectMapper;

/**
 * AI 业务层测试（Week3 / Day16-Day20）。
 *
 * <p><b>为什么这一层不 mock ChatClient？</b>
 * 用真实的 {@link ChatClient} + {@link MockChatModel}，能顺带验证
 * "Spring AI 的调用链是否真的通了"（Prompt 组装、ChatResponse 提取、流式转换）。
 * 如果连 ChatClient 也 mock 掉，测试只证明"Java 方法调用没错"，
 * 证明不了"框架集成没错" —— 而后者恰恰是接新框架时最容易坏的地方。
 *
 * <p>只 mock 掉"外部副作用"：调用记录（要写数据库）和指标（要注册表）。
 */
class AiServiceImplTest {

	private final ObjectMapper objectMapper = new ObjectMapper();

	private AiProperties properties;

	private AiCallLogService callLogService;

	private AiServiceImpl service;

	@BeforeEach
	void setUp() {
		properties = new AiProperties();
		properties.setProvider("mock");
		properties.setMaxAttempts(3);
		properties.setRetryBaseMillis(1);
		properties.setMaxRepairAttempts(1);
		properties.getMock().setLatencyMillis(0);
		properties.setMockChunkDelayMillis(0);

		callLogService = mock(AiCallLogService.class);
		ChatClient chatClient = ChatClient.create(new MockChatModel(properties));
		service = new AiServiceImpl(chatClient, new AiInvoker(properties), callLogService,
				new AiMetrics(new SimpleMeterRegistry(), properties), properties,
				new TaskDraftParser(objectMapper), objectMapper);
	}

	@Test
	@DisplayName("问答：应返回非空回答，并记录一次成功调用")
	void chat_shouldReturnAnswerAndRecordSuccess() {
		ChatAnswer answer = service.chat(1L, "帮我说明一下这个系统能做什么");

		assertThat(answer.answer()).isNotBlank();
		assertThat(answer.meta().attempts()).isEqualTo(1);
		assertThat(answer.meta().traceId()).isNotBlank();
		assertThat(answer.meta().provider()).isEqualTo("mock");
		verify(callLogService).recordSuccess(eq(1L), eq(AiScene.CHAT), any(AiChatResult.class));
	}

	@Test
	@DisplayName("问答：上游超时应重试到上限后抛 AI_TIMEOUT，并记录失败")
	void chat_whenTimeout_shouldRetryThenFail() {
		assertThatThrownBy(() -> service.chat(1L, MockChatModel.FAIL_TIMEOUT + " 你好"))
				.isInstanceOfSatisfying(AiException.class, ex -> {
					assertThat(ex.getCode()).isEqualTo(AiErrorCode.TIMEOUT);
					assertThat(ex.getAttempts()).isEqualTo(3);
				});

		verify(callLogService).recordFailure(eq(1L), eq(AiScene.CHAT), any(), any(AiException.class), anyLong());
	}

	@Test
	@DisplayName("问答：限流同样应重试，但错误码保持 AI_RATE_LIMITED")
	void chat_whenRateLimited_shouldKeepCode() {
		assertThatThrownBy(() -> service.chat(1L, MockChatModel.FAIL_RATE_LIMIT))
				.isInstanceOfSatisfying(AiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo(AiErrorCode.RATE_LIMITED));
	}

	@Test
	@DisplayName("摘要：超长输出应截断到上限")
	void summarize_shouldTruncateToLimit() {
		SummaryResponse response = service.summarize(1L, "这是一段需要被压缩的原文。".repeat(40), 40);

		assertThat(response.summary()).isNotBlank();
		assertThat(response.summary().length()).isLessThanOrEqualTo(40);
	}

	@Test
	@DisplayName("分类：应返回白名单内的枚举名")
	void classify_shouldReturnWhitelistedCategory() {
		ClassifyResponse response = service.classify(1L, "账单扣费金额不对，发票也错了");

		assertThat(response.category()).isEqualTo("BILLING");
		assertThat(response.reason()).isNotBlank();
	}

	@Test
	@DisplayName("任务草稿：应返回校验通过的草稿，且不落库")
	void draftTask_shouldReturnValidDraft() {
		TaskDraftResponse draft = service.draftTask(1L, "明天下午把季度报告写完，这个比较重要");

		assertThat(draft.title()).isNotBlank();
		assertThat(draft.priority()).isEqualTo(4);
		assertThat(draft.dueDate()).isNotNull();
		assertThat(draft.modelCalls()).isEqualTo(1);
	}

	@Test
	@DisplayName("任务草稿：模型输出不是 JSON 时应修复重试一次后抛 422")
	void draftTask_whenInvalidJson_shouldRepairOnceThenFail() {
		assertThatThrownBy(() -> service.draftTask(1L, MockChatModel.FAIL_INVALID_JSON + " 明天交报告"))
				.isInstanceOfSatisfying(AiException.class,
						ex -> assertThat(ex.getCode()).isEqualTo(AiErrorCode.INVALID_OUTPUT));

		// 首次 + 一次修复 = 两次模型调用，每次都记了一条失败
		verify(callLogService, times(2)).recordFailure(eq(1L), eq(AiScene.TASK_DRAFT), any(),
				any(AiException.class), anyLong());
	}

	@Test
	@DisplayName("流式：应把增量回调给调用方，并在结束时给出完整结果")
	void streamChat_shouldEmitDeltasAndComplete() throws Exception {
		StringBuilder received = new StringBuilder();
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<AiChatResult> completed = new AtomicReference<>();

		service.streamChat(1L, "介绍一下流式输出", received::append,
				result -> {
					completed.set(result);
					latch.countDown();
				},
				error -> latch.countDown());

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(completed.get()).isNotNull();
		assertThat(received).isNotEmpty();
		assertThat(received.toString()).isEqualTo(completed.get().content());
	}

	@Test
	@DisplayName("流式：上游失败应走 onError，而不是静默结束")
	void streamChat_whenUpstreamFails_shouldCallOnError() throws Exception {
		CountDownLatch latch = new CountDownLatch(1);
		AtomicReference<AiException> error = new AtomicReference<>();

		service.streamChat(1L, MockChatModel.FAIL_TIMEOUT, delta -> {
		}, result -> latch.countDown(), ex -> {
			error.set(ex);
			latch.countDown();
		});

		assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
		assertThat(error.get()).isNotNull();
		assertThat(error.get().getCode()).isEqualTo(AiErrorCode.TIMEOUT);
	}

}
