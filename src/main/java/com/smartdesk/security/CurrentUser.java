package com.smartdesk.security;

import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/**
 * 当前登录用户上下文（Day11）。
 *
 * <p><b>为什么不用 ThreadLocal 手动传？</b>
 *
 * <p>Spring Security 已经把认证信息放在 {@code SecurityContextHolder} 里，
 * 我们只需要一个静态读取入口。好处是：
 * <ul>
 *   <li>Controller 和 Service 都不用改方法签名</li>
 *   <li>不依赖 ThreadLocal 传递，异步场景（@Async、CompletableFuture）
 *       也不会出现"线程池复用导致上下文串号"的 bug</li>
 * </ul>
 *
 * <p><b>面试可讲：</b>
 * Spring Security 6 默认使用 {@code DeferredSecurityContextHolder} + 虚拟线程，
 * 不再是传统的 ThreadLocal 存储模式，正是为了解决线程池复用问题。
 */
public final class CurrentUser {

	private CurrentUser() {
		// 工具类，禁止实例化
	}

	/**
	 * 获取当前登录用户 id。
	 *
	 * @return 用户 id；未登录返回 {@code null}
	 */
	public static Long getUserId() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()) {
			return null;
		}
		Object principal = auth.getPrincipal();
		if (principal instanceof JwtPrincipal jwtPrincipal) {
			return jwtPrincipal.userId();
		}
		return null;
	}

	/**
	 * 获取当前登录用户名。
	 *
	 * @return 用户名；未登录返回 {@code null}
	 */
	public static String getUsername() {
		Authentication auth = SecurityContextHolder.getContext().getAuthentication();
		if (auth == null || !auth.isAuthenticated()) {
			return null;
		}
		Object principal = auth.getPrincipal();
		if (principal instanceof JwtPrincipal jwtPrincipal) {
			return jwtPrincipal.username();
		}
		return null;
	}

}
