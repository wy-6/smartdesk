package com.smartdesk.knowledge.prompt;

import java.util.List;

import com.smartdesk.knowledge.dto.ChunkResponse;

/**
 * RAG Prompt 模板（Week5 / Day33）。
 *
 * <p><b>本类的核心是"四条规则"，每条都对应一种真实的翻车方式：</b>
 * <ol>
 *   <li><b>只能依据资料回答</b> —— 否则模型会用通用知识补全，
 *       看起来答得很顺，但内容在用户的制度文件里根本不存在</li>
 *   <li><b>每条结论标注来源编号</b> —— 否则用户没法判断哪句话可信，
 *       也让"引用错误"无法被检测</li>
 *   <li><b>资料不足必须拒答</b> —— 这是最容易被忽略、后果最严重的一条。
 *       模型倾向于"尽力回答"，而"尽力"在企业知识库场景里等于编造</li>
 *   <li><b>资料矛盾要指出来</b> —— 制度文件经常有版本冲突，
 *       模型挑一个说就掩盖了问题</li>
 * </ol>
 *
 * <p><b>资料编号怎么给？</b>按检索得分从高到低编号（[1] 最相关）。
 * 编号和 {@code citations} 列表严格对齐 —— 前端拿到 citations[0] 就知道
 * 模型说的 [1] 对应哪份文档的哪一片。
 */
public final class RagPromptTemplates {

	/** 拒答的标准话术。用户能一眼看出"这是没查到"，而不是"这是模型不想说"。 */
	public static final String REFUSAL_ANSWER = "根据现有资料无法确认这个问题。";

	/** 资料区块的开始标记。 */
	public static final String MATERIAL_HEADER = "【资料】";

	private RagPromptTemplates() {
	}

	/**
	 * 系统提示词。
	 */
	public static String ragSystem() {
		return """
				你是 SmartDesk 的知识库助手。你只能依据下面提供的【资料】回答用户问题。

				必须遵守的规则：
				1. 只使用【资料】中的事实。不要用资料以外的知识补充、推测或举例。
				2. 每条结论后面标注来源编号，格式如 [1]、[2]。编号对应资料条目前的编号。
				3. 如果【资料】里没有回答问题所需的信息，必须回答"根据现有资料无法确认这个问题。"，
				   不允许猜测、不允许说"一般来说""通常情况下"。
				4. 如果【资料】之间互相矛盾，直接指出矛盾点，并说明分别出自哪几条资料。
				5. 回答用简体中文，尽量简洁，不超过 300 字。

				注意：你的回答会被系统逐条核对引用编号。编造不存在的编号会被判为引用错误。
				""";
	}

	/**
	 * 把检索结果组装成资料区块。
	 *
	 * @param hits 检索到的片段（已按分数排序）
	 * @return 资料文本
	 */
	public static String materials(List<ChunkResponse> hits) {
		StringBuilder sb = new StringBuilder();
		sb.append(MATERIAL_HEADER).append('\n');
		for (int i = 0; i < hits.size(); i++) {
			ChunkResponse hit = hits.get(i);
			sb.append('[').append(i + 1).append("]（来源：").append(hit.documentTitle());
			if (hit.pageNo() != null) {
				sb.append(" 第 ").append(hit.pageNo()).append(" 页");
			}
			sb.append("，片段 #").append(hit.chunkIndex()).append("）\n");
			sb.append(hit.content()).append("\n\n");
		}
		return sb.toString().strip();
	}

	/**
	 * 用户消息：资料 + 问题。
	 *
	 * <p>注意<b>问题放在资料后面</b>：长上下文里"最后的问题"权重更高，
	 * 放在后面模型不容易漏看。
	 */
	public static String userMessage(List<ChunkResponse> hits, String question) {
		return materials(hits) + "\n\n问题：" + question;
	}

}
