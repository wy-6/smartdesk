package com.smartdesk.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * JWT 服务单元测试（Day13）。
 *
 * <p>重点测三件事：
 * <ol>
 *   <li>签发的令牌能被解析出正确的 userId</li>
 *   <li>被篡改的令牌必须解析失败</li>
 *   <li>用别的密钥签的令牌必须解析失败</li>
 * </ol>
 *
 * <p>第 2、3 条是 JWT 安全性的核心 —— 签名机制存在的意义就在于此。
 */
class JwtServiceTest {

	/** 测试用密钥，长度必须 >= 32 字节（HS256 要求）。 */
	private static final String SECRET = "test-secret-key-for-jwt-unit-test-0123456789abc";

	private JwtService newService() {
		return new JwtService(SECRET, 3600L);
	}

	@Test
	@DisplayName("签发的令牌应能解析出正确的 userId 和 username")
	void issueToken_shouldBeParsable() {
		JwtService jwtService = newService();

		String token = jwtService.issueToken(42L, "alice");

		assertThat(token).isNotNull();
		assertThat(token.split("\\.")).hasSize(3);
		assertThat(jwtService.parseUserId(token)).isEqualTo(42L);
		assertThat(jwtService.parseUsername(token)).isEqualTo("alice");
	}

	@Test
	@DisplayName("篡改签名的令牌必须解析失败")
	void parseUserId_whenSignatureTampered_shouldReturnNull() {
		JwtService jwtService = newService();
		String token = jwtService.issueToken(42L, "alice");

		// 把签名部分换掉
		String[] parts = token.split("\\.");
		String tampered = parts[0] + "." + parts[1] + ".forgedsignature";

		assertThat(jwtService.parseUserId(tampered)).isNull();
	}

	@Test
	@DisplayName("用其他密钥签发的令牌必须解析失败")
	void parseUserId_whenSignedWithOtherKey_shouldReturnNull() {
		JwtService issuer = newService();
		JwtService verifier = new JwtService("a-completely-different-secret-key-0123456789", 3600L);

		String token = issuer.issueToken(42L, "alice");

		assertThat(verifier.parseUserId(token)).isNull();
	}

	@Test
	@DisplayName("篡改 payload 也必须解析失败（签名会不匹配）")
	void parseUserId_whenPayloadTampered_shouldReturnNull() {
		JwtService jwtService = newService();
		String token = jwtService.issueToken(42L, "alice");

		String[] parts = token.split("\\.");
		// 把 payload 的 id 从 42 改成 999（base64url 编码）
		String forgedPayload = java.util.Base64.getUrlEncoder().withoutPadding()
				.encodeToString("{\"sub\":\"999\",\"username\":\"alice\"}".getBytes(java.nio.charset.StandardCharsets.UTF_8));
		String forged = parts[0] + "." + forgedPayload + "." + parts[2];

		assertThat(jwtService.parseUserId(forged)).isNull();
	}

	@Test
	@DisplayName("非法格式的令牌应解析失败而不是抛异常")
	void parseUserId_whenMalformed_shouldReturnNull() {
		JwtService jwtService = newService();

		assertThat(jwtService.parseUserId("not-a-jwt")).isNull();
		assertThat(jwtService.parseUserId("")).isNull();
		assertThat(jwtService.parseUserId("a.b.c")).isNull();
	}

	@Test
	@DisplayName("过期令牌应解析失败")
	void parseUserId_whenExpired_shouldReturnNull() throws InterruptedException {
		// 有效期设为 1 秒，等 2 秒让它过期
		JwtService jwtService = new JwtService(SECRET, 1L);
		String token = jwtService.issueToken(42L, "alice");

		Thread.sleep(2000);

		assertThat(jwtService.parseUserId(token)).isNull();
	}

	@Test
	@DisplayName("有效期应返回配置的秒数")
	void getExpirationSeconds_shouldReturnConfiguredValue() {
		JwtService jwtService = new JwtService(SECRET, 7200L);

		assertThat(jwtService.getExpirationSeconds()).isEqualTo(7200L);
	}

}
