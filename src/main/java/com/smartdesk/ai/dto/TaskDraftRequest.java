package com.smartdesk.ai.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/** 任务草稿提取请求（Week3 / Day18）。 */
public record TaskDraftRequest(
		@NotBlank(message = "text 不能为空")
		@Size(max = 2000, message = "text 不能超过 2000 字") String text) {
}
