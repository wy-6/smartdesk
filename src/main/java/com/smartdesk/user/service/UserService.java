package com.smartdesk.user.service;

import com.smartdesk.user.entity.AppUser;

/**
 * 用户业务接口（Day11）。
 */
public interface UserService {

	/**
	 * 注册用户。
	 *
	 * @param username 登录名
	 * @param rawPassword 明文密码（内部会用 BCrypt 哈希后存储）
	 * @param nickname 昵称
	 * @return 创建后的用户
	 * @throws com.smartdesk.common.BusinessException 用户名已存在时抛出
	 */
	AppUser register(String username, String rawPassword, String nickname);

	/**
	 * 校验用户名密码是否正确。
	 *
	 * @param username 登录名
	 * @param rawPassword 明文密码
	 * @return 校验通过返回用户对象，否则返回 {@code null}
	 */
	AppUser authenticate(String username, String rawPassword);

	/**
	 * 按 id 查询用户。
	 *
	 * @param id 用户 id
	 * @return 用户；不存在返回 {@code null}
	 */
	AppUser getById(Long id);

}
