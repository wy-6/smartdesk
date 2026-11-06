package com.smartdesk.ai.dto;

import java.util.Map;

import com.smartdesk.ai.tool.ToolSpec;

/**
 * 工具说明（Week4 / Day23）。
 *
 * <p><b>为什么要把工具列表开放成接口？</b>
 * 三个用途：前端可以据此渲染"AI 能做什么"的提示；运维可以确认线上注册了哪些工具
 * （多注册一个写操作就是多一个攻击面）；测试可以直接断言工具集。
 *
 * <p>返回的 schema 就是<b>模型看到的那一份</b>，不做任何裁剪 ——
 * 这样才能真正验证"userId 不在 schema 里"。
 */
public record ToolDescriptor(String name, String description, boolean sideEffect, Map<String, Object> parameters) {

	public static ToolDescriptor from(ToolSpec spec) {
		return new ToolDescriptor(spec.name(), spec.description(), spec.sideEffect(), spec.parameterSchema());
	}

}
