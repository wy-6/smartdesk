package com.smartdesk.ai.tool;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * 标记一个工具有副作用（Week4 / Day25）。
 *
 * <p>有副作用的工具<b>不会被执行</b>，只会生成一份待确认草稿。
 * 这是 Agent 类应用最重要的一条安全规则：
 *
 * <blockquote>
 * 模型可以提议做任何事，但只有人能批准它真的做。
 * </blockquote>
 *
 * <p>为什么不能"在 Prompt 里让模型先确认"？
 * 因为 Prompt 是软约束：模型可能被注入绕过、可能忘了问、可能问完自己就当用户同意了。
 * 而确认必须是<b>确定性的代码路径</b> —— 用户点了一个按钮，调了一个接口，才落库。
 *
 * <p>这里没有复用 Spring AI 的 {@code @Tool(returnDirect = ...)}，
 * 因为 {@code returnDirect} 的语义是"工具结果直接返回不再问模型"，和副作用无关。
 * 借用它会让后来的人误解，不如新加一个语义精确的注解。
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SideEffect {

	/** 人类可读的操作描述，出现在确认提示里。 */
	String value() default "";

}
