package com.smartdesk.knowledge.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

/**
 * 创建知识库文档请求（Week5 / Day30）。
 *
 * <p><b>注意：这里没有 ownerId 字段。</b>归属人永远来自 JWT，
 * 绝不接受请求体传入 —— 这是本项目从第 1 周起就没破过的规矩。
 *
 * @param title 文档标题
 * @param content 文档正文（直接粘贴文本时用）
 * @param sourceType 来源类型：TEXT / MARKDOWN / PDF。不填按纯文本处理
 */
public record DocumentCreateRequest(
		@NotBlank(message = "title 不能为空")
		@Size(max = 200, message = "title 最多 200 字")
		String title,

		@Size(max = 200000, message = "content 最多 200000 字")
		String content,

		String sourceType) {

}
