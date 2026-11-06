package com.smartdesk.ai.model;

/**
 * 分类结果的可选值（Week3 / Day17）。
 *
 * <p><b>为什么要用枚举约束模型输出？</b>
 * 模型最擅长的就是"给你一个看起来很像但不在约定里的答案"：
 * 你说分类是 BUG/FEATURE/BILLING，它可能回 {@code "Bug "}、{@code "缺陷"}、
 * {@code "BUG（疑似）"}。如果不做白名单校验，下游按分类统计就全乱了。
 *
 * <p>所以流程必须是：<b>模型输出 → 解析 → 白名单校验 → 才允许进入业务</b>。
 */
public enum TaskCategory {

	BUG, FEATURE, BILLING, ACCOUNT, OTHER;

	/** 严格匹配，大小写与空格不容忍；匹配不上返回 {@code null}。 */
	public static TaskCategory fromCode(String code) {
		if (code == null) {
			return null;
		}
		try {
			return valueOf(code.strip().toUpperCase());
		}
		catch (IllegalArgumentException ex) {
			return null;
		}
	}

}
