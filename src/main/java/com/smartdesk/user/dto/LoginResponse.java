package com.smartdesk.user.dto;

/**
 * 登录响应（Day11）。
 *
 * <p><b>Day9 要点：VO（View Object）是给前端看的对象。</b>
 *
 * <p>它和实体的区别：
 * <ul>
 *   <li>实体（Entity）关心数据库怎么存，字段可能很"技术"</li>
 *   <li>VO 关心前端怎么用，字段是"业务语义"的</li>
 * </ul>
 *
 * <p>典型例子：实体里有 {@code passwordHash}，VO 里绝不能有。
 * 还有 {@code enabled} 这种纯内部状态，VO 里往往也不需要。
 *
 * <p><b>另一个真实例子：</b>数据库里存的是 {@code createdAt}（LocalDateTime），
 * 但前端要的是"3分钟前"这种相对时间，或者 ISO-8601 带时区偏移的字符串。
 * 这些转换逻辑放在 VO 的工厂方法里最合适。
 *
 * @param token JWT 令牌
 * @param userId 用户 id
 * @param username 用户名
 * @param nickname 昵称
 * @param expiresIn 令牌有效期（秒）
 */
public record LoginResponse(
		String token,
		Long userId,
		String username,
		String nickname,
		long expiresIn) {
}
