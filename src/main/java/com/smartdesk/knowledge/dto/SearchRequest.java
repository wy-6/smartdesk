package com.smartdesk.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 知识检索请求（Week5 / Day32）。
 *
 * @param query 查询语句
 * @param topK 召回数量，不填用配置默认值；上限由服务端 clamp
 * @param threshold 相似度阈值，不填用配置默认值
 */
public record SearchRequest(
		@NotBlank(message = "query 不能为空")
		@Size(max = 500, message = "query 最多 500 字")
		String query,
		Integer topK,
		Double threshold) {

}
