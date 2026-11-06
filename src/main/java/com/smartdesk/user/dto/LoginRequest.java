package com.smartdesk.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 登录请求（Day11）。
 *
 * <p><b>Day9 要点：DTO 的校验注解是"声明式"的边界校验。</b>
 * 写完注解，Spring MVC 会自动拦截非法请求并抛
 * {@code MethodArgumentNotValidException}，由全局异常处理器转成 400。
 * 你不需要在 Controller 里写一行 if。
 *
 * <p>这比手工校验好在哪？
 * <ul>
 *   <li>不会漏 —— 新增字段时校验规则跟着字段走，忘了加不会编译报错，
 *       但这是"漏网"风险；手工 if 则容易漏掉某个分支</li>
 *   <li>可复用 —— 同一个 DTO 在多个接口用，规则不用重复</li>
 *   <li>可测试 —— 校验规则本身也可以写成单元测试</li>
 * </ul>
 */
public class LoginRequest {

	@NotBlank(message = "用户名不能为空")
	@Size(max = 64, message = "用户名长度不能超过 64")
	private String username;

	@NotBlank(message = "密码不能为空")
	@Size(min = 6, max = 64, message = "密码长度必须在 6-64 之间")
	private String password;

	public String getUsername() {
		return username;
	}

	public void setUsername(String username) {
		this.username = username;
	}

	public String getPassword() {
		return password;
	}

	public void setPassword(String password) {
		this.password = password;
	}

}
