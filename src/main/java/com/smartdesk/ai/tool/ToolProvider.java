package com.smartdesk.ai.tool;

/**
 * 工具集标记接口（Week4 / Day23）。
 *
 * <p>任何实现了本接口的 Spring Bean，其标了 {@code @Tool} 的方法都会被
 * {@link ToolRegistry} 自动注册。
 *
 * <p><b>为什么用一个"空接口"而不是扫描整个包？</b>
 * 扫描包会把所有带 {@code @Tool} 的类都注册进来 —— 包括以后某个第三方库
 * 自带的工具类。工具就是攻击面，注册范围必须是显式的白名单，
 * 而不是"碰巧被扫到"。加一个 {@code implements ToolProvider} 的成本很低，
 * 但它逼着开发者做一次"我确实要暴露这个能力"的决定。
 *
 * <p>第 6 周接入 RAG 时，{@code KnowledgeTools} 同样实现这个接口即可，
 * {@link ToolRegistry} 一行都不用改。
 */
public interface ToolProvider {

}
