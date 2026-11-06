package com.smartdesk.knowledge.domain;

/**
 * 文档来源类型（Week5 / Day30）。
 *
 * <p>为什么要记来源类型，而不是只记扩展名？
 * 因为<b>解析策略不同，且来源类型是引用元数据的一部分</b>：
 * 用户看到引用时，应该知道"这是从一份 PDF 制度里抽出来的第 3 页"，
 * 而不是"来自某个 .pdf 文件"。
 */
public enum SourceType {

	/** 纯文本。 */
	TEXT("text/plain"),

	/** Markdown。 */
	MARKDOWN("text/markdown"),

	/** PDF。用 PDFBox 抽取文本。 */
	PDF("application/pdf");

	private final String mimeType;

	SourceType(String mimeType) {
		this.mimeType = mimeType;
	}

	public String getMimeType() {
		return mimeType;
	}

	/**
	 * 从文件名后缀推断来源类型。
	 *
	 * @param fileName 文件名
	 * @return 识别出的类型；不认识的后缀返回 null（由调用方决定是否拒绝）
	 */
	public static SourceType fromFileName(String fileName) {
		if (fileName == null) {
			return null;
		}
		String lower = fileName.toLowerCase();
		if (lower.endsWith(".pdf")) {
			return PDF;
		}
		if (lower.endsWith(".md") || lower.endsWith(".markdown")) {
			return MARKDOWN;
		}
		if (lower.endsWith(".txt")) {
			return TEXT;
		}
		return null;
	}

}
