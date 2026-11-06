package com.smartdesk.ai.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.model.AiScene;

/**
 * 重试器测试（Week3 / Day19）。
 *
 * <p>重试是"最容易写错、也最容易出事"的一段逻辑，必须逐条验证：
 * 什么错重试、重试几次、次数用完怎么办、不可重试的错误是否只打一次。
 */
class AiInvokerTest {

	private AiInvoker invoker(int maxAttempts) {
		AiProperties properties = new AiProperties();
		properties.setMaxAttempts(maxAttempts);
		properties.setRetryBaseMillis(1);
		return new AiInvoker(properties);
	}

	@Test
	@DisplayName("一次成功时 attempts 应为 1")
	void execute_successFirstTime_shouldReportOneAttempt() {
		AiInvoker.Attempt<String> attempt = invoker(3).execute(AiScene.CHAT, () -> "ok");

		assertThat(attempt.value()).isEqualTo("ok");
		assertThat(attempt.attempts()).isEqualTo(1);
	}

	@Test
	@DisplayName("可重试错误失败两次后成功，attempts 应为 3")
	void execute_retryableFailureThenSuccess_shouldRetry() {
		AtomicInteger counter = new AtomicInteger();

		AiInvoker.Attempt<String> attempt = invoker(3).execute(AiScene.CHAT, () -> {
			if (counter.incrementAndGet() < 3) {
				throw new AiException(AiErrorCode.UPSTREAM_ERROR, "上游抖动");
			}
			return "ok";
		});

		assertThat(attempt.attempts()).isEqualTo(3);
		assertThat(counter.get()).isEqualTo(3);
	}

	@Test
	@DisplayName("不可重试错误（输出不合法）只应尝试一次")
	void execute_nonRetryable_shouldNotRetry() {
		AtomicInteger counter = new AtomicInteger();

		assertThatThrownBy(() -> invoker(3).execute(AiScene.CLASSIFY, () -> {
			counter.incrementAndGet();
			throw new AiException(AiErrorCode.INVALID_OUTPUT, "分类不在白名单");
		})).isInstanceOf(AiException.class);

		assertThat(counter.get()).isEqualTo(1);
	}

	@Test
	@DisplayName("重试次数用尽后应抛出带 attempts 的异常")
	void execute_exhausted_shouldThrowWithAttempts() {
		AtomicInteger counter = new AtomicInteger();

		assertThatThrownBy(() -> invoker(3).execute(AiScene.CHAT, () -> {
			counter.incrementAndGet();
			throw new AiException(AiErrorCode.TIMEOUT, "超时");
		}))
				.isInstanceOf(AiException.class)
				.satisfies(ex -> assertThat(((AiException) ex).getAttempts()).isEqualTo(3));

		assertThat(counter.get()).isEqualTo(3);
	}

	@Test
	@DisplayName("退避时间应随次数指数增长，并带随机抖动")
	void backoff_shouldGrowExponentially() {
		AiInvoker invoker = invoker(5);

		long first = invoker.backoffMillis(1);
		long third = invoker.backoffMillis(3);

		assertThat(first).isBetween(1L, 2L);
		assertThat(third).isGreaterThanOrEqualTo(4L);
	}

}
