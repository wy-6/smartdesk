package com.smartdesk.ai.service;

import java.time.Duration;

import org.springframework.stereotype.Component;

import com.smartdesk.ai.config.AiProperties;
import com.smartdesk.ai.model.AiChatResult;
import com.smartdesk.ai.model.AiErrorCode;
import com.smartdesk.ai.model.AiScene;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * AI 调用的可观测指标（Week3 / Day19）。
 *
 * <p><b>没有指标的 AI 功能等于黑盒：</b>你不知道钱花在哪个场景、P99 是多少、
 * 失败率有没有突然上升。这三个问题正好对应三类指标：
 * <ul>
 *   <li><b>Counter</b>（只增不减）：{@code ai.calls{scene,provider,outcome}} —— 调用量与成功率</li>
 *   <li><b>Timer</b>：{@code ai.latency{scene}} —— 延迟分布（P50/P95/P99）</li>
 *   <li><b>Counter</b>：{@code ai.tokens{scene,type}} —— Token 消耗，用来算成本</li>
 * </ul>
 *
 * <p><b>为什么用 tag 而不是每个场景建一个指标名？</b>
 * 指标名爆炸会让 Prometheus 基数失控；tag 能聚合也能下钻，
 * 这是"高基数"问题的标准解法（同理：不要把 userId 做成 tag）。
 */
@Component
public class AiMetrics {

	private final MeterRegistry registry;

	private final AiProperties properties;

	public AiMetrics(MeterRegistry registry, AiProperties properties) {
		this.registry = registry;
		this.properties = properties;
	}

	public void recordSuccess(AiScene scene, AiChatResult result) {
		String provider = provider();
		counter(scene, provider, "success").increment();
		Timer.builder("ai.latency")
				.tag("scene", scene.getCode())
				.tag("provider", provider)
				.register(registry)
				.record(Duration.ofMillis(Math.max(0, result.latencyMs())));
		if (result.promptTokens() > 0) {
			registry.counter("ai.tokens", "scene", scene.getCode(), "type", "prompt")
					.increment(result.promptTokens());
		}
		if (result.completionTokens() > 0) {
			registry.counter("ai.tokens", "scene", scene.getCode(), "type", "completion")
					.increment(result.completionTokens());
		}
	}

	public void recordFailure(AiScene scene, AiErrorCode code, long latencyMs) {
		String provider = provider();
		counter(scene, provider, "error").increment();
		registry.counter("ai.errors", "scene", scene.getCode(), "provider", provider, "code", code.getCode())
				.increment();
		Timer.builder("ai.latency")
				.tag("scene", scene.getCode())
				.tag("provider", provider)
				.register(registry)
				.record(Duration.ofMillis(Math.max(0, latencyMs)));
	}

	/**
	 * 工具执行指标（Week4 / Day26）。
	 *
	 * <p><b>为什么工具要单独一套指标，而不是混进 {@code ai.calls}？</b>
	 * 因为排查方向完全不同：模型失败要看上游，工具失败要看自己的数据库和代码。
	 * 混在一起看，一次"工具 NPE"会被误判成"模型服务抖动"。
	 *
	 * <p>tag 里刻意不放 traceId / userId —— 那是高基数，会让 Prometheus 内存爆掉。
	 * 具体的 trace 查询走 {@code ai_tool_call_log} 表。
	 */
	public void recordToolCall(String toolName, boolean ok, long latencyMs) {
		registry.counter("ai.tool.calls", "tool", toolName, "outcome", ok ? "success" : "error").increment();
		Timer.builder("ai.tool.latency")
				.tag("tool", toolName)
				.register(registry)
				.record(Duration.ofMillis(Math.max(0, latencyMs)));
	}

	/**
	 * 越权参数注入尝试（Week4 / Day24）。
	 *
	 * <p>这个指标一旦非零就该告警：说明有人在用 Prompt 注入试图让模型
	 * 传 {@code userId}。虽然我们剥离了参数不会真的越权，
	 * 但"有人正在尝试"本身就是需要关注的安全信号。
	 */
	public void recordPrivilegeStripped() {
		registry.counter("ai.tool.privilege_stripped").increment();
	}

	private Counter counter(AiScene scene, String provider, String outcome) {
		return registry.counter("ai.calls", "scene", scene.getCode(), "provider", provider, "outcome", outcome);
	}

	private String provider() {
		return properties.getProvider() == null ? "unknown" : properties.getProvider().toLowerCase();
	}

}
