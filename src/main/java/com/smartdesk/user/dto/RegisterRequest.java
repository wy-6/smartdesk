package com.smartdesk.user.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

/**
 * 注册请求（Day11）。
 *
 * <p><b>密码强度校验的正则</b>：至少 8 位，且同时包含字母和数字。
 *
 * <p>这是"最小强度要求"，不是"充分强度要求"。面试可讲：
 * 真正的密码强度应该用 zxcvbn 这类专门的库（能识别
 * "Password123!" 这种"看起来复杂实则常见"的密码），
 * 正则只能做最基础的兜底。
 */
public class RegisterRequest {

	@NotBlank(message = "用户名不能为空")
	@Size(min = 3, max = 64, message = "用户名长度必须在 3-64 之间")
	@Pattern(regexp = "^[a-zA-Z0-9_]+$", message = "用户名只能包含字母、数字和下划线")
	private String username;

	@NotBlank(message = "密码不能为空")
	@Size(min = 8, max = 64, message = "密码长度必须在 8-64 之间")
	@Pattern(regexp = "^(?=.*[A-Za-z])(?=.*\\d).+$", message = "密码必须同时包含字母和数字")
	private String password;

	@Size(max = 64, message = "昵称长度不能超过 64")
	private String nickname;

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

	public String getNickname() {
		return nickname;
	}

	public void setNickname(String nickname) {
		this.nickname = nickname;
	}

}
