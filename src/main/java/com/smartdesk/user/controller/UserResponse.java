package com.smartdesk.user.controller;

import com.smartdesk.user.entity.AppUser;

/**
 * 用户信息响应（Day11）。
 *
 * <p><b>这是"防密码泄露"的最后一道防线。</b>
 *
 * <p>整个系统有三道防线保证密码哈希不会泄露：
 * <ol>
 *   <li>Service 层返回的实体不会被直接序列化 —— Controller 显式转成这个 VO</li>
 *   <li>VO 里根本没有 passwordHash 字段</li>
 *   <li>实体 {@code AppUser} 上还有 {@code @JsonIgnore} 兜底</li>
 * </ol>
 *
 * @param id 用户 id
 * @param username 用户名
 * @param nickname 昵称
 */
public record UserResponse(Long id, String username, String nickname) {

	/** 从实体转换，不带任何敏感字段。 */
	public static UserResponse from(AppUser user) {
		return new UserResponse(user.getId(), user.getUsername(), user.getNickname());
	}

}
