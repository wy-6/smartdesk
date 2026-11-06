package com.smartdesk.ai.tool;

import java.util.Map;

/**
 * 有副作用工具返回的"待确认"结果（Week4 / Day25）。
 *
 * <p>{@code status} 固定为 {@code PENDING_CONFIRMATION}，模型看到这个值
 * 就知道"事情还没做完"，不能对用户说"已创建"。
 *
 * <p><b>{@code message} 是写给模型看的，不是写给用户看的。</b>
 * 工具结果会作为 tool message 回喂给模型，模型再转述给用户。
 * 所以这里要明确写"请告诉用户需要确认"，而不是让模型自己猜该怎么措辞 ——
 * 小模型经常会自作主张地说"已经为你创建好了"。
 *
 * @param status 固定 PENDING_CONFIRMATION
 * @param confirmationId 确认号
 * @param expiresInSeconds 多少秒后过期
 * @param preview 草稿内容，供前端渲染确认卡片
 * @param message 给模型的提示语
 */
public record ToolConfirmation(String status, String confirmationId, long expiresInSeconds, Map<String, Object> preview,
		String message) {

	public static final String PENDING = "PENDING_CONFIRMATION";

}
