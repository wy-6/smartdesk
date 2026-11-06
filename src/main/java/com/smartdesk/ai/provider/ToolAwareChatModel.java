package com.smartdesk.ai.provider;

import java.util.List;

import org.springframework.ai.chat.model.ChatModel;
import org.springframework.ai.chat.model.ChatResponse;
import org.springframework.ai.chat.prompt.Prompt;

import com.smartdesk.ai.tool.ToolSpec;

/**
 * 支持工具调用的聊天模型（Week4 / Day23）。
 *
 * <p><b>为什么单独抽一个接口，而不是直接往 {@code ChatModel} 上加参数？</b>
 * {@code ChatModel} 是 Spring AI 的接口，我们改不了。而"能带工具"是一种<b>可选能力</b>：
 * 本地 Ollama 的某些小模型、或者未来接入的某个只支持纯文本的服务，并不具备。
 * 用一个子接口表达"我支持工具调用"，调用方就可以优雅降级：
 * <pre>
 * if (chatModel instanceof ToolAwareChatModel toolModel) {
 *     toolModel.call(prompt, tools);
 * } else {
 *     chatModel.call(prompt);   // 退化成普通问答，功能少但不报错
 * }
 * </pre>
 *
 * <p><b>为什么不复用 Spring AI 的 {@code ToolCallingChatOptions}？</b>
 * 那条路要求 ChatModel 内部实现执行循环（{@code DefaultToolCallingManager}），
 * 而本项目的轮数上限、重复调用熔断、超时预算都需要精确控制，
 * 放在框架内部就插不进来了。这里返回的 {@code ChatResponse} 里
 * <b>只带 tool_calls，不执行</b>，执行由 {@code AgentLoop} 负责。
 *
 * <p>这个分工是本周架构的核心：
 * <pre>
 * Provider 层  —— 只负责"协议翻译"：把 tools 发上去，把 tool_calls 解析回来
 * AgentLoop 层 —— 负责"策略"：调几轮、调几个、什么时候停
 * </pre>
 */
public interface ToolAwareChatModel extends ChatModel {

	/**
	 * 带工具定义调用模型。
	 *
	 * @param prompt 对话
	 * @param tools 本次允许使用的工具。传空列表等价于 {@link #call(Prompt)}
	 * @return 响应；若模型决定调用工具，{@code getResult().getOutput().hasToolCalls()} 为 true，
	 *         但<b>工具尚未执行</b>
	 */
	ChatResponse call(Prompt prompt, List<ToolSpec> tools);

}
