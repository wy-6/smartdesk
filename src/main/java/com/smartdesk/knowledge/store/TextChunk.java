package com.smartdesk.knowledge.store;

/**
 * 一个切片（Week5 / Day30）。
 *
 * @param index 文档内序号，从 0 开始
 * @param content 切片正文
 * @param charStart 在原文中的起始偏移
 * @param charEnd 在原文中的结束偏移（不含）
 */
public record TextChunk(int index, String content, int charStart, int charEnd) {

	public int length() {
		return charEnd - charStart;
	}

}
