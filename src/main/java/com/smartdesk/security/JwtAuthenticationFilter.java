package com.smartdesk.security;

import java.io.IOException;
import java.util.List;

import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.authentication.WebAuthenticationDetailsSource;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * JWT 认证过滤器（Day11）。
 *
 * <p><b>认证流程：</b>
 * <pre>
 * 请求进来 → 本过滤器拦截
 *   → 从 Authorization 头取 "Bearer xxx"
 *   → JwtService 校验签名和有效期
 *   → 有效：构造 JwtPrincipal，塞进 SecurityContext
 *   → 无效/缺失：什么都不做（放行给后续的授权逻辑决定是否拒绝）
 * </pre>
 *
 * <p><b>关键设计：过滤器不做拦截，只做"标记身份"。</b>
 *
 * <p>真正的"哪些接口需要登录"由 {@code SecurityConfig} 决定。
 * 这样分工的好处：
 * <ul>
 *   <li>过滤器专注于解析令牌，职责单一</li>
 *   <li>接口的开放/关闭策略集中在配置类，一眼能看全</li>
 * </ul>
 *
 * <p><b>面试可讲：</b>无状态认证 vs Session 认证。
 * JWT 是无状态的（服务端不存会话），适合微服务和前后端分离；
 * 代价是无法即时撤销（要撤销得引入黑名单或短有效期 + 刷新令牌）。
 */
@Component
public class JwtAuthenticationFilter extends OncePerRequestFilter {

	private static final String HEADER = "Authorization";

	private static final String PREFIX = "Bearer ";

	private final JwtService jwtService;

	public JwtAuthenticationFilter(JwtService jwtService) {
		this.jwtService = jwtService;
	}

	@Override
	protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain chain)
			throws ServletException, IOException {

		String header = request.getHeader(HEADER);
		if (header != null && header.startsWith(PREFIX)) {
			String token = header.substring(PREFIX.length());
			Long userId = jwtService.parseUserId(token);
			if (userId != null) {
				JwtPrincipal principal = new JwtPrincipal(userId, extractUsername(token));
				UsernamePasswordAuthenticationToken authentication = new UsernamePasswordAuthenticationToken(
						principal, null, List.of(new SimpleGrantedAuthority("ROLE_USER")));
				authentication.setDetails(new WebAuthenticationDetailsSource().buildDetails(request));
				SecurityContextHolder.getContext().setAuthentication(authentication);
			}
		}

		// 无论如何都要继续链条，由授权逻辑决定放行还是拒绝
		chain.doFilter(request, response);
	}

	/** 从令牌中取用户名，取不到不影响主流程（userId 已经够用了）。 */
	private String extractUsername(String token) {
		try {
			return jwtService.parseUsername(token);
		}
		catch (Exception ex) {
			return null;
		}
	}

}
