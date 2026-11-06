package com.smartdesk.knowledge.service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.regex.Pattern;

import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;

import com.smartdesk.common.BusinessException;
import com.smartdesk.knowledge.domain.SourceType;

/**
 * 文档文本抽取（Week5 / Day30）。
 *
 * <p><b>为什么按"页"抽取，而不是把整篇拼成一大段？</b>
 * 因为引用溯源要能告诉用户"这句话来自第几页"。PDF 天然有页，
 * 按页切开之后每个切片都带页码，用户点开引用能直接翻到原文位置。
 * TXT / Markdown 没有页码概念，就当成第 1 页。
 *
 * <p><b>面试可讲：</b>解析格式这一步看着简单，实际坑很多：
 * <ul>
 *   <li>PDF 有文本层和图片层两种。扫描件（图片 PDF）抽出来是空的，
 *       必须给出明确提示"需要 OCR"，而不是返回空文档让用户以为导入成功</li>
 *   <li>Markdown 的链接语法 {@code [标题](url)} 里的 url 对语义检索是噪音，
 *       要剥掉，只留标题文本</li>
 *   <li>代码围栏 ``` 里的代码对制度类问答是噪音，但保留原样也无害，
 *       主要是为了不让围栏符号污染向量</li>
 * </ul>
 */
public class DocumentTextExtractor {

	/** Markdown 链接：[文字](链接) → 文字。 */
	private static final Pattern MD_LINK = Pattern.compile("\\[([^\\]]*)]\\([^)]*\\)");

	/** Markdown 代码围栏标记。 */
	private static final String MD_FENCE = "```";

	/**
	 * 抽取文本。
	 *
	 * @param content 原始字节
	 * @param sourceType 来源类型
	 * @return 按页（或整篇）切好的文本片段
	 * @throws BusinessException 内容为空或格式不支持
	 */
	public List<PageText> extract(byte[] content, SourceType sourceType) {
		if (content == null || content.length == 0) {
			throw new BusinessException("KNOWLEDGE_DOCUMENT_EMPTY", "文档内容为空，无法导入");
		}
		return switch (sourceType) {
			case TEXT -> List.of(new PageText(1, decodeUtf8(content)));
			case MARKDOWN -> List.of(new PageText(1, cleanMarkdown(decodeUtf8(content))));
			case PDF -> extractPdf(content);
		};
	}

	private String decodeUtf8(byte[] content) {
		return new String(content, StandardCharsets.UTF_8);
	}

	/**
	 * Markdown 轻清洗：去掉链接地址与代码围栏标记，保留正文与标题文字。
	 */
	private String cleanMarkdown(String text) {
		String cleaned = MD_LINK.matcher(text).replaceAll("$1");
		cleaned = cleaned.replace(MD_FENCE, "");
		return cleaned.strip();
	}

	/**
	 * PDF 按页抽取。
	 *
	 * <p>逐页调用 {@link PDFTextStripper}：PDFBox 的 stripper 可以设置页码范围，
	 * 一次一页地抽，才能知道每段文字属于第几页。
	 */
	private List<PageText> extractPdf(byte[] content) {
		List<PageText> pages = new ArrayList<>();
		try (PDDocument document = Loader.loadPDF(content)) {
			int pageCount = document.getNumberOfPages();
			if (pageCount == 0) {
				throw new BusinessException("KNOWLEDGE_DOCUMENT_EMPTY", "PDF 没有任何页面");
			}
			PDFTextStripper stripper = new PDFTextStripper();
			stripper.setSortByPosition(true);
			for (int page = 1; page <= pageCount; page++) {
				stripper.setStartPage(page);
				stripper.setEndPage(page);
				String text = stripper.getText(document).strip();
				if (!text.isEmpty()) {
					pages.add(new PageText(page, text));
				}
			}
		}
		catch (IOException ex) {
			throw new BusinessException("KNOWLEDGE_PARSE_FAILED", "PDF 解析失败，文件可能已损坏或被加密", ex);
		}
		catch (IllegalArgumentException ex) {
			throw new BusinessException("KNOWLEDGE_PARSE_FAILED", "PDF 解析失败：" + ex.getMessage(), ex);
		}
		if (pages.isEmpty()) {
			throw new BusinessException("KNOWLEDGE_DOCUMENT_EMPTY",
					"PDF 中没有可抽取的文字（可能是扫描件，需要先做 OCR）");
		}
		return pages;
	}

	/**
	 * 一页文本。
	 *
	 * @param pageNo 页码，从 1 开始
	 * @param text 该页正文
	 */
	public record PageText(int pageNo, String text) {
	}

}
