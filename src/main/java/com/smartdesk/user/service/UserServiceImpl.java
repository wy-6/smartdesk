package com.smartdesk.user.service;

import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.smartdesk.common.BusinessException;
import com.smartdesk.user.entity.AppUser;
import com.smartdesk.user.mapper.AppUserMapper;

/**
 * 用户业务实现（Day11）。
 *
 * <p><b>密码存储的三个核心要点：</b>
 *
 * <ol>
 *   <li><b>绝不明文存储</b>：用 BCrypt（Spring Security 内置）哈希后存。
 *       <br>为什么不用 MD5/SHA1？它们是为"快速摘要"设计的，可以被彩虹表秒破，
 *       而且没有盐（salt）时相同密码哈希值相同。
 *       <br>BCrypt 的设计目标就是"故意慢"：默认 cost=10，暴力破解成本被放大上千倍，
 *       而且它自动把盐拼进哈希串里，每个用户即使密码相同哈希也不同。</li>
 *
 *   <li><b>不存明文日志</b>：注册和登录的日志里绝不出现 rawPassword。</li>
 *
 *   <li><b>防用户枚举</b>：登录失败时，响应统一是"用户名或密码错误"，
 *       不区分"用户不存在"还是"密码错了"。否则攻击者能用错误信息
 *       探测出哪些用户名真实存在。</li>
 * </ol>
 */
@Service
public class UserServiceImpl implements UserService {

	private final AppUserMapper userMapper;

	private final PasswordEncoder passwordEncoder;

	public UserServiceImpl(AppUserMapper userMapper, PasswordEncoder passwordEncoder) {
		this.userMapper = userMapper;
		this.passwordEncoder = passwordEncoder;
	}

	@Override
	@Transactional
	public AppUser register(String username, String rawPassword, String nickname) {
		// 唯一索引 uk_username 会兜底，但这里先查一次给出友好提示。
		// 注意：并发场景下这个 if 判断不可靠，真正的保证来自数据库唯一索引。
		AppUser existing = userMapper.selectByUsername(username);
		if (existing != null) {
			throw new BusinessException("USERNAME_EXISTS", "用户名已被占用");
		}

		AppUser user = new AppUser();
		user.setUsername(username);
		// 关键：哈希后存储，原文永不落库
		user.setPasswordHash(passwordEncoder.encode(rawPassword));
		user.setNickname(nickname);
		user.setEnabled(Boolean.TRUE);

		userMapper.insert(user);
		return user;
	}

	@Override
	public AppUser authenticate(String username, String rawPassword) {
		AppUser user = userMapper.selectByUsername(username);

		if (user == null) {
			// 用户不存在时也要走一次哈希校验，防止攻击者通过响应时间
			// 区分"用户不存在"（立即返回）和"密码错误"（耗时计算）。
			// 这是"时序攻击"的常见防御手段。
			passwordEncoder.encode("dummy-password-for-timing-equalization");
			return null;
		}

		if (!Boolean.TRUE.equals(user.getEnabled())) {
			return null;
		}

		// matches 内部会按哈希里的 salt 和 cost 重新计算并比对
		if (!passwordEncoder.matches(rawPassword, user.getPasswordHash())) {
			return null;
		}
		return user;
	}

	@Override
	public AppUser getById(Long id) {
		return userMapper.selectById(id);
	}

}
