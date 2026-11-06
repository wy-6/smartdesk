package com.smartdesk.ai.service;

import java.util.concurrent.ThreadLocalRandom;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiException;
import com.smartdesk.ai.model.AiScene;

/**
 * AI 调用重试器（Week3 / Day19）。
 *
 * <p><b>重试不是"失败了再来一次"这么简单，它有三个必须回答的问题：</b>
 * <ol>
 *   <li><b>什么错值得重试？</b>只看"可重试错误码"：超时、429、5xx。
 *       参数错（400）、输出不合法（422）重试一万次也还是错，只会放大成本</li>
 *   <li><b>重试几次？</b>有上限。没有上限的重试会把一次上游抖动放大成雪崩</li>
 *   <li><b>隔多久重试？</b>指数退避 + 随机抖动：{@code base * 2^n + random}。
 *       固定间隔会让所有请求在同一时刻再次涌向已经过载的上游（惊群）</li>
 * </ol>
 *
 * <p><b>为什么这里不做熔断？</b>
 * 熔断器（Resilience4j）适合"上游持续故障时快速失败"，本项目调用量小、
 * 且第 19 天的验收重点是"重试有上限 + 错误可观测"，加熔断会增加配置复杂度
 * 而不增加可讲的技术深度。面试时能说清"什么场景才需要熔断"比硬塞一个组件更好。
 */
@Component
public class AiInvoker {

	private static final Logger log = LoggerFactory.getLogger(AiInvoker.class);

	private final AiProperties properties;

	public AiInvoker(AiProperties properties) {
		this.properties = properties;
	}

	/**
	 * 执行一次带重试的模型调用。
	 *
	 * @param scene 场景，仅用于日志
	 * @param action 真正的调用动作
	 * @param <T> 返回值类型
	 * @return 结果 + 实际尝试次数
	 */
	public <T> Attempt<T> execute(AiScene scene, Supplier<T> action) {
		int maxAttempts = Math.max(1, properties.getMaxAttempts());
		for (int attempt = 1;; attempt++) {
			try {
				return new Attempt<>(action.get(), attempt);
			}
			catch (AiException ex) {
				boolean lastAttempt = attempt >= maxAttempts;
				if (lastAttempt || !ex.getCode().isRetryable()) {
					// 回填尝试次数，让上层日志、响应和调用记录都能看到"打了几次"
					throw ex.withAttempts(attempt);
				}
				long backoff = backoffMillis(attempt);
				log.warn("AI 调用失败，{}ms 后重试（scene={} attempt={}/{} code={} msg={}）", backoff,
						scene.getCode(), attempt, maxAttempts, ex.getCode().getCode(), ex.getMessage());
				sleep(backoff);
			}
		}
	}

	/**
	 * 第 n 次失败后的等待时间：base * 2^(n-1)，再加 0~base/2 的随机抖动。
	 *
	 * <p>为什么必须加抖动？假设 100 个请求同时被限流，不加抖动它们会在
	 * 完全相同的时刻重试，第二轮依然限流 —— 这就是"重试风暴"。
	 */
	long backoffMillis(int attempt) {
		long base = Math.max(1, properties.getRetryBaseMillis());
		long exponential = base * (1L << Math.min(attempt - 1, 5));
		long jitter = ThreadLocalRandom.current().nextLong(Math.max(1, base / 2));
		return exponential + jitter;
	}

	private static void sleep(long millis) {
		try {
			Thread.sleep(millis);
		}
		catch (InterruptedException ex) {
			Thread.currentThread().interrupt();
			throw new AiException(AiErrorCode.UPSTREAM_ERROR, "重试等待被中断", ex);
		}
	}

	/**
	 * 调用结果。
	 *
	 * @param value 返回值
	 * @param attempts 实际尝试次数（1 表示一次成功，没有重试）
	 */
	public record Attempt<T>(T value, int attempts) {
	}

}
