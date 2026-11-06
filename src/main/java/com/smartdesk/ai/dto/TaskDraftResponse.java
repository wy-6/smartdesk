package com.smartdesk.ai.dto;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 任务草稿响应（Week3 / Day18）。
 *
 * <p><b>它是"草稿"，不是"任务"。</b>接口不写数据库，第 4 周才会加"确认后创建"。
 * 这个设计的价值在于：<b>模型永远不能直接产生副作用</b>。
 * 只有经过校验、经过用户确认的数据，才允许进入 {@code task} 表。
 *
 * @param title 标题
 * @param description 描述
 * @param priority 优先级
 * @param dueDate 截止时间
 * @param warnings 已通过校验但值得提示的点（例如截止时间已过期）
 * @param modelCalls 产生这个草稿一共调了几次模型（含修复重试）
 * @param meta 最后一次调用元信息
 */
public record TaskDraftResponse(String title, String description, Integer priority, LocalDateTime dueDate,
		List<String> warnings, int modelCalls, AiMeta meta) {
}
