package com.smartdesk.config;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpStatus;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.authentication.UsernamePasswordAuthenticationFilter;

import com.smartdesk.common.ApiResponse;
import com.smartdesk.security.JwtAuthenticationFilter;

import tools.jackson.databind.ObjectMapper;

/**
 * Spring Security 配置（Day11）。
 *
 * <p><b>核心配置项逐条解释：</b>
 *
 * <ol>
 *   <li><b>csrf.disable()</b>：CSRF 防护针对的是"浏览器自动带 Cookie"这一机制。
 *       我们用 JWT 且 Token 存在请求头（不是 Cookie）里，浏览器不会自动附带，
 *       所以 CSRF 风险不存在。这是前后端分离项目的标准做法。</li>
 *
 *   <li><b>SessionCreationPolicy.STATELESS</b>：不创建 HttpSession。
 *       每次请求靠 Token 自证身份，服务端不存任何会话状态。</li>
 *
 *   <li><b>authorizeHttpRequests</b>：URL 权限规则。
 *       注意顺序 —— permitAll 的要写在前面。</li>
 *
 *   <li><b>authenticationEntryPoint</b>：未认证（401）时的统一响应。
 *       默认返回 HTML 错误页，API 项目应该返回 JSON。</li>
 *
 *   <li><b>accessDeniedHandler</b>：已认证但权限不足（403）时的响应。</li>
 * </ol>
 */
@Configuration
@EnableWebSecurity
public class SecurityConfig {

	private final JwtAuthenticationFilter jwtAuthenticationFilter;

	private final ObjectMapper objectMapper;

	public SecurityConfig(JwtAuthenticationFilter jwtAuthenticationFilter, ObjectMapper objectMapper) {
		this.jwtAuthenticationFilter = jwtAuthenticationFilter;
		this.objectMapper = objectMapper;
	}

	@Bean
	public SecurityFilterChain filterChain(HttpSecurity http) throws Exception {
		http
				// 关闭 CSRF：JWT 存在请求头，浏览器不会自动携带
				.csrf(csrf -> csrf.disable())
				// 无状态：不使用 HttpSession
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// 不需要登录页和默认登录表单
				.httpBasic(basic -> basic.disable())
				.formLogin(form -> form.disable())
				// 授权规则
				.authorizeHttpRequests(auth -> auth
						// 公开接口
						.requestMatchers("/api/auth/login", "/api/auth/register").permitAll()
						.requestMatchers("/api/health", "/actuator/health").permitAll()
						.requestMatchers("/error").permitAll()
						// 其余全部需要认证
						.anyRequest().authenticated())
				// 统一 401 响应
				.exceptionHandling(ex -> ex
						.authenticationEntryPoint((request, response, authException) -> {
							response.setStatus(HttpStatus.UNAUTHORIZED.value());
							response.setContentType("application/json;charset=UTF-8");
							response.getWriter().write(objectMapper.writeValueAsString(
									ApiResponse.failure("UNAUTHORIZED", "未登录或令牌已失效")));
						})
						// 统一 403 响应
						.accessDeniedHandler((request, response, deniedException) -> {
							response.setStatus(HttpStatus.FORBIDDEN.value());
							response.setContentType("application/json;charset=UTF-8");
							response.getWriter().write(objectMapper.writeValueAsString(
									ApiResponse.failure("FORBIDDEN", "无权访问该资源")));
						}))
				// 把 JWT 过滤器插到 UsernamePasswordAuthenticationFilter 之前
				.addFilterBefore(jwtAuthenticationFilter, UsernamePasswordAuthenticationFilter.class);

		return http.build();
	}

	/**
	 * BCrypt 密码编码器。
	 *
	 * <p>注意这里的构造：Spring Security 6 中 {@code BCryptPasswordEncoder}
	 * 默认 strength=10。调高会显著变慢（登录接口响应时间增加），
	 * 调低则削弱抗暴力破解能力。生产环境常见做法是 10~12。
	 */
	@Bean
	public PasswordEncoder passwordEncoder() {
		return new BCryptPasswordEncoder();
	}

}
