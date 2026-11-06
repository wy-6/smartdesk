package com.smartdesk.ai.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 摘要请求（Week3 / Day17）。
 *
 * <p>记录类型的紧凑构造器可以给可选字段兜默认值，
 * 这样 Controller 不用写 {@code request.maxChars() == null ? 120 : ...}。
 */
public record SummaryRequest(
		@NotBlank(message = "text 不能为空")
		@Size(max = 20000, message = "text 不能超过 20000 字") String text,

		@Min(value = 20, message = "maxChars 至少 20")
		@Max(value = 500, message = "maxChars 最多 500")
		Integer maxChars) {

	public SummaryRequest {
		if (maxChars == null) {
			maxChars = 120;
		}
	}

}
