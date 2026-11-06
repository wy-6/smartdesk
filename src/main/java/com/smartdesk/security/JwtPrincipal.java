package com.smartdesk.security;

/**
 * 认证主体，承载当前登录用户的信息（Day11）。
 *
 * <p>用 record 而不是普通类：
 * <ul>
 *   <li>不可变 —— 认证信息一旦确定就不该被改</li>
 *   <li>自动有 equals/hashCode —— Spring Security 的一些比较逻辑依赖它</li>
 *   <li>代码量少</li>
 * </ul>
 *
 * @param userId 用户 id
 * @param username 用户名
 */
public record JwtPrincipal(Long userId, String username) {
}
