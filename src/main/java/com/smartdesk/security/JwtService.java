package com.smartdesk.security;

import java.time.Duration;
import java.util.Date;

import javax.crypto.SecretKey;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;

/**
 * JWT 签发与校验（Day11）。
 *
 * <p><b>JWT 的三个组成部分</b>（形如 {@code xxx.yyy.zzz}）：
 * <ul>
 *   <li><b>Header</b>：算法信息（HS256），Base64URL 编码</li>
 *   <li><b>Payload</b>：业务声明（如 userId、username、过期时间），Base64URL 编码</li>
 *   <li><b>Signature</b>：对前两段用密钥做 HMAC-SHA256 签名，防篡改</li>
 * </ul>
 *
 * <p><b>关键认知：JWT 是"签名"不是"加密"。</b>
 * Payload 部分只是 Base64URL 编码，<b>不是加密</b>，任何人都能解出来看内容。
 * 所以 JWT 里绝不能放密码、身份证号等敏感信息。
 * 它的作用只是"证明这个 Token 是我签发的，且没被篡改"。
 *
 * <p><b>为什么选 HS256 而不用 RS256？</b>
 * 单服务部署 HS256 够用（对称密钥，签发和验证用同一个密钥）。
 * 微服务场景下验证方和签发方分离，才需要 RS256（非对称）。
 */
@Component
public class JwtService {

	/** 签名密钥。生产环境必须用环境变量注入，不能用默认值。 */
	private final SecretKey secretKey;

	/** 过期时长，默认 2 小时。 */
	private final Duration expiration;

	public JwtService(
			@Value("${smartdesk.jwt.secret}") String secret,
			@Value("${smartdesk.jwt.expiration-seconds:7200}") long expirationSeconds) {
		// HS256 要求密钥至少 256 位（32 字节）。这是 JWA 规范的规定，
		// 用短密钥会直接抛 WeakKeyException。
		this.secretKey = Keys.hmacShaKeyFor(secret.getBytes(java.nio.charset.StandardCharsets.UTF_8));
		this.expiration = Duration.ofSeconds(expirationSeconds);
	}

	/**
	 * 签发 Token。
	 *
	 * @param userId 用户 id
	 * @param username 用户名
	 * @return JWT 字符串
	 */
	public String issueToken(Long userId, String username) {
		Date now = new Date();
		Date expiry = new Date(now.getTime() + expiration.toMillis());
		return Jwts.builder()
				.subject(String.valueOf(userId))
				.claim("username", username)
				.issuedAt(now)
				.expiration(expiry)
				.signWith(secretKey)
				.compact();
	}

	/**
	 * 解析并校验 Token。
	 *
	 * @param token JWT 字符串
	 * @return 解析出的用户 id；无效或过期返回 {@code null}
	 */
	public Long parseUserId(String token) {
		Claims claims = parseClaims(token);
		if (claims == null) {
			return null;
		}
		try {
			return Long.valueOf(claims.getSubject());
		}
		catch (NumberFormatException ex) {
			return null;
		}
	}

	/**
	 * 解析并校验 Token，取用户名。
	 *
	 * @param token JWT 字符串
	 * @return 用户名；无效或过期返回 {@code null}
	 */
	public String parseUsername(String token) {
		Claims claims = parseClaims(token);
		return claims == null ? null : claims.get("username", String.class);
	}

	/**
	 * 解析令牌为 Claims，失败统一返回 {@code null}。
	 *
	 * @param token JWT 字符串
	 * @return Claims；无效或过期返回 {@code null}
	 */
	private Claims parseClaims(String token) {
		try {
			return Jwts.parser()
					.verifyWith(secretKey)
					.build()
					.parseSignedClaims(token)
					.getPayload();
		}
		catch (Exception ex) {
			// 任何解析失败（签名不对、格式错误、已过期）都统一按"无效令牌"处理。
			// 这里不打印详细异常到日志的 warn 级别，避免日志被刷屏；
			// 需要排查时用 debug 级别。
			return null;
		}
	}

	/**
	 * 取 Token 的过期时间戳（秒）。
	 *
	 * @param token JWT 字符串
	 * @return 过期时间戳；解析失败返回 0
	 */
	public long getExpiresAt(String token) {
		try {
			Claims claims = Jwts.parser()
					.verifyWith(secretKey)
					.build()
					.parseSignedClaims(token)
					.getPayload();
			return claims.getExpiration().getTime() / 1000;
		}
		catch (Exception ex) {
			return 0L;
		}
	}

	/** 获取令牌有效期（秒），供登录接口返回给前端。 */
	public long getExpirationSeconds() {
		return expiration.getSeconds();
	}

}
