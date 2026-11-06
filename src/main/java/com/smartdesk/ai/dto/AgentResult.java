package com.smartdesk.ai.dto;

import java.util.List;

/**
 * Agent 执行结果（Week4 / Day22）。
 *
 * <p>相比第 3 周的 {@code ChatAnswer}，多了三个字段，每个都对应一个新产品问题：
 * <ul>
 *   <li>{@code toolCalls}：模型做了什么？—— 可解释性</li>
 *   <li>{@code rounds}：绕了几圈？—— 成本与性能</li>
 *   <li>{@code meta.latencyMs}：整轮耗时 —— 用户等待时间</li>
 * </ul>
 *
 * @param answer 最终自然语言回答
 * @param traceId 本次请求追踪号
 * @param toolCalls 工具调用轨迹，按执行顺序
 * @param rounds 实际模型调用轮数
 * @param meta 汇总元信息（token 为累计值，耗时为整个循环的耗时）
 */
public record AgentResult(String answer, String traceId, List<ToolCallTrace> toolCalls, int rounds, AiMeta meta) {

}
