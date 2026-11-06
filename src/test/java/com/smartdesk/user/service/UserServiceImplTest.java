package com.smartdesk.user.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.security.crypto.password.PasswordEncoder;

import com.smartdesk.common.BusinessException;
import com.smartdesk.user.entity.AppUser;
import com.smartdesk.user.mapper.AppUserMapper;

/**
 * UserService 单元测试（Day13）。
 *
 * <p><b>重点测三件事：</b>
 * <ol>
 *   <li>密码是哈希存储的，不是明文</li>
 *   <li>用户不存在和密码错误，返回结果完全一致（防用户枚举）</li>
 *   <li>用户已存在时注册要失败</li>
 * </ol>
 *
 * <p>注意这里用的是<b>真实的</b> {@link BCryptPasswordEncoder} 而不是 mock ——
 * 因为我们要验证的正是"哈希行为"本身。BCrypt 默认 cost=10，
 * 单次计算约 50-100ms，10 个测试加起来 1 秒内，可以接受。
 */
@ExtendWith(MockitoExtension.class)
class UserServiceImplTest {

	private static final String RAW_PASSWORD = "alice123456";

	@Mock
	private AppUserMapper userMapper;

	private PasswordEncoder passwordEncoder;

	private UserServiceImpl userService;

	@BeforeEach
	void setUp() {
		// 用真实编码器，验证哈希行为
		passwordEncoder = new BCryptPasswordEncoder();
		userService = new UserServiceImpl(userMapper, passwordEncoder);
	}

	@Test
	@DisplayName("注册时密码应以 BCrypt 哈希存储，绝不能存明文")
	void register_shouldStoreHashedPassword() {
		when(userMapper.selectByUsername("alice")).thenReturn(null);
		when(userMapper.insert(any(AppUser.class))).thenReturn(1);

		AppUser user = userService.register("alice", RAW_PASSWORD, "爱丽丝");

		assertThat(user.getPasswordHash()).isNotNull();
		assertThat(user.getPasswordHash()).isNotEqualTo(RAW_PASSWORD);
		// BCrypt 哈希以 $2a$/$2b$/$2y$ 开头
		assertThat(user.getPasswordHash()).startsWith("$2");
		assertThat(user.getUsername()).isEqualTo("alice");
		verify(userMapper).insert(any(AppUser.class));
	}

	@Test
	@DisplayName("同一密码两次注册应产生不同哈希（BCrypt 自动加盐）")
	void register_samePasswordTwice_shouldProduceDifferentHashes() {
		when(userMapper.selectByUsername(any())).thenReturn(null);
		when(userMapper.insert(any(AppUser.class))).thenReturn(1);

		AppUser u1 = userService.register("alice", RAW_PASSWORD, null);
		AppUser u2 = userService.register("bob", RAW_PASSWORD, null);

		assertThat(u1.getPasswordHash()).isNotEqualTo(u2.getPasswordHash());
	}

	@Test
	@DisplayName("用户名已存在时注册应抛业务异常")
	void register_whenUsernameExists_shouldThrow() {
		AppUser existing = new AppUser();
		existing.setId(1L);
		existing.setUsername("alice");
		when(userMapper.selectByUsername("alice")).thenReturn(existing);

		assertThatThrownBy(() -> userService.register("alice", RAW_PASSWORD, null))
				.isInstanceOf(BusinessException.class)
				.hasMessageContaining("用户名已被占用");

		verify(userMapper, never()).insert(any(AppUser.class));
	}

	@Test
	@DisplayName("密码正确应认证通过")
	void authenticate_whenPasswordCorrect_shouldReturnUser() {
		AppUser user = new AppUser();
		user.setId(1L);
		user.setUsername("alice");
		user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
		user.setEnabled(Boolean.TRUE);
		when(userMapper.selectByUsername("alice")).thenReturn(user);

		AppUser result = userService.authenticate("alice", RAW_PASSWORD);

		assertThat(result).isNotNull();
		assertThat(result.getId()).isEqualTo(1L);
	}

	@Test
	@DisplayName("密码错误应返回 null")
	void authenticate_whenPasswordWrong_shouldReturnNull() {
		AppUser user = new AppUser();
		user.setId(1L);
		user.setUsername("alice");
		user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
		user.setEnabled(Boolean.TRUE);
		when(userMapper.selectByUsername("alice")).thenReturn(user);

		assertThat(userService.authenticate("alice", "wrongpassword")).isNull();
	}

	@Test
	@DisplayName("用户不存在应返回 null（与密码错误表现一致，防用户枚举）")
	void authenticate_whenUserNotFound_shouldReturnNull() {
		when(userMapper.selectByUsername("nobody")).thenReturn(null);

		assertThat(userService.authenticate("nobody", "whatever123456")).isNull();
	}

	@Test
	@DisplayName("账号被禁用时应认证失败")
	void authenticate_whenDisabled_shouldReturnNull() {
		AppUser user = new AppUser();
		user.setId(1L);
		user.setUsername("alice");
		user.setPasswordHash(passwordEncoder.encode(RAW_PASSWORD));
		user.setEnabled(Boolean.FALSE);
		when(userMapper.selectByUsername("alice")).thenReturn(user);

		assertThat(userService.authenticate("alice", RAW_PASSWORD)).isNull();
	}

}
