package com.smartdesk.user.controller;

import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.smartdesk.common.ApiResponse;
import com.smartdesk.security.JwtService;
import com.smartdesk.user.dto.LoginRequest;
import com.smartdesk.user.dto.LoginResponse;
import com.smartdesk.user.dto.RegisterRequest;
import com.smartdesk.user.entity.AppUser;
import com.smartdesk.user.service.UserService;

import jakarta.validation.Valid;

/**
 * 认证接口（Day11）。
 *
 * <p><b>接口清单：</b>
 * <ul>
 *   <li>{@code POST /api/auth/register} — 注册，返回用户基本信息</li>
 *   <li>{@code POST /api/auth/login} — 登录，返回 JWT</li>
 * </ul>
 *
 * <p>这两个接口在 {@code SecurityConfig} 里被配置为 {@code permitAll()}，
 * 也就是免登录访问。
 */
@RestController
@RequestMapping("/api/auth")
public class AuthController {

	private final UserService userService;

	private final JwtService jwtService;

	public AuthController(UserService userService, JwtService jwtService) {
		this.userService = userService;
		this.jwtService = jwtService;
	}

	/**
	 * 注册新用户。
	 *
	 * @param request 注册请求
	 * @return 新用户信息（不含密码哈希）
	 */
	@PostMapping("/register")
	public ResponseEntity<ApiResponse<UserResponse>> register(@Valid @RequestBody RegisterRequest request) {
		AppUser user = userService.register(request.getUsername(), request.getPassword(), request.getNickname());
		return ResponseEntity.status(HttpStatus.CREATED)
				.body(ApiResponse.success(UserResponse.from(user)));
	}

	/**
	 * 登录换取 JWT。
	 *
	 * @param request 登录请求
	 * @return JWT 令牌与用户信息
	 */
	@PostMapping("/login")
	public ResponseEntity<ApiResponse<LoginResponse>> login(@Valid @RequestBody LoginRequest request) {
		AppUser user = userService.authenticate(request.getUsername(), request.getPassword());

		if (user == null) {
			// 关键：用户不存在和密码错误，返回完全相同的提示。
			// 否则攻击者可以用错误信息探测出哪些用户名存在（用户枚举漏洞）。
			// 状态码也用 401 而不是 404，语义更准确。
			return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
					.body(ApiResponse.failure("BAD_CREDENTIALS", "用户名或密码错误"));
		}

		String token = jwtService.issueToken(user.getId(), user.getUsername());
		LoginResponse response = new LoginResponse(
				token,
				user.getId(),
				user.getUsername(),
				user.getNickname(),
				jwtService.getExpirationSeconds());
		return ResponseEntity.ok(ApiResponse.success(response));
	}

}
