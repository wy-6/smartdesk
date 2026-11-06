package com.smartdesk.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识库问答请求（Week5 / Day33）。
 *
 * @param question 问题
 * @param topK 召回数量，不填用配置默认值
 */
public record AskRequest(
		@NotBlank(message = "question 不能为空")
		@Size(max = 1000, message = "question 最多 1000 字")
		String question,
		Integer topK) {

}
