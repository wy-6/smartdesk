package com.smartdesk.ai.dto;

import jakarta.validation.constraints.Size;

/**
 * 创建会话请求（Week4 / Day27）。
 *
 * @param title 标题，可为空 —— 为空时用第一条用户消息自动生成
 */
public record SessionCreateRequest(@Size(max = 200, message = "标题不能超过 200 字") String title) {

}
