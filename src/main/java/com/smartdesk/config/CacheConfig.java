package com.smartdesk.config;

import java.time.Duration;
import java.util.concurrent.ThreadLocalRandom;

import org.springframework.cache.CacheManager;
import org.springframework.cache.annotation.EnableCaching;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.cache.BatchStrategies;
import org.springframework.data.redis.cache.BatchStrategy;
import org.springframework.data.redis.cache.RedisCacheConfiguration;
import org.springframework.data.redis.cache.RedisCacheManager;
import org.springframework.data.redis.cache.RedisCacheWriter;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.serializer.GenericJacksonJsonRedisSerializer;
import org.springframework.data.redis.serializer.RedisSerializationContext;
import org.springframework.data.redis.serializer.StringRedisSerializer;

import tools.jackson.databind.jsontype.BasicPolymorphicTypeValidator;

/**
 * Redis 缓存配置（Day10）。
 *
 * <p><b>三个必须配置的项，缺一个就出 bug：</b>
 *
 * <ol>
 *   <li><b>序列化方式</b>：默认的 {@code JdkSerializationRedisSerializer}
 *       会把对象序列化成 Java 二进制格式。可读性差（redis-cli 看到乱码），
 *       而且有 <b>反序列化漏洞风险</b>（历史上出现过攻击者构造恶意字节流导致 RCE）。
 *       改成 JSON 序列化，安全且可读。</li>
 *
 *   <li><b>TTL（过期时间）</b>：不设 TTL 的缓存叫"永久缓存"，
 *       改了数据库数据就再也读不到新值了，是线上事故的常见来源。</li>
 *
 *   <li><b>空值不缓存</b>：{@code disableCachingNullValues()}，
 *       防止缓存穿透（查不存在的 id 每次都打到数据库）。</li>
 * </ol>
 *
 * <p><b>Boot 4 / Spring Data Redis 4 的变化：</b>
 * 序列化器类名里的 "Jackson2" 是历史命名残留（对应 Jackson 2）。
 * 现在 Spring Data Redis 4 已经全面迁移到 Jackson 3，
 * 新类是 {@link GenericJacksonJsonRedisSerializer}，
 * 它内部用的是 {@code tools.jackson.databind.ObjectMapper}。
 * 用旧的 {@code GenericJackson2JsonRedisSerializer} 反而会出问题。
 */
@Configuration
@EnableCaching
public class CacheConfig {

	/** 缓存基础 TTL（秒），30 分钟。 */
	private static final long BASE_TTL_SECONDS = 1800L;

	/** 随机偏移上限（秒），用于防缓存雪崩。 */
	private static final long TTL_RANDOM_RANGE = 60L;

	/**
	 * 缓存管理器。
	 *
	 * @param connectionFactory Redis 连接工厂（Spring Boot 自动配置）
	 * @return 缓存管理器
	 */
	@Bean
	public CacheManager cacheManager(RedisConnectionFactory connectionFactory) {
		// 用 Jackson 3 的 GenericJacksonJsonRedisSerializer。
		//
		// 【关键】必须显式 enableDefaultTyping()！
		//
		// Spring Data Redis 4 里 defaultTyping 的默认值是 false（Jackson 2 时代是 true）。
		// 这是因为"多态类型信息"是反序列化漏洞的常见来源 —— 攻击者如果能往缓存里
		// 写入恶意 JSON，就可能触发任意类实例化。Jackson 3 默认关掉它，是更安全的默认。
		//
		// 但我们缓存的是自己的 Task 对象，需要还原成正确的类型。
		// 不开的话，缓存里存的是 {"id":1,"title":"..."}，
		// 反序列化回来是 LinkedHashMap，调用 getTitle() 直接抛：
		//   ClassCastException: LinkedHashMap cannot be cast to Task
		//
		// typeValidator 限定了"只有哪些包下的类可以被反序列化"，
		// 这是开启 defaultTyping 后必须加的安全护栏 —— 防止任意类实例化。
		GenericJacksonJsonRedisSerializer jsonSerializer = GenericJacksonJsonRedisSerializer.builder()
				.enableDefaultTyping(BasicPolymorphicTypeValidator.builder()
						// 只允许自己项目包下的类被反序列化
						.allowIfBaseType("com.smartdesk.")
						.build())
				.build();

		RedisCacheConfiguration config = RedisCacheConfiguration.defaultCacheConfig()
				// key 用 String 序列化，redis-cli 里能直接看到 key 名，便于排查
				.serializeKeysWith(RedisSerializationContext.SerializationPair
						.fromSerializer(new StringRedisSerializer()))
				// value 用 JSON 序列化，可读且安全
				.serializeValuesWith(RedisSerializationContext.SerializationPair
						.fromSerializer(jsonSerializer))
				// 不缓存 null，防止缓存穿透
				.disableCachingNullValues();

		// 防雪崩：给每个 key 的 TTL 加随机偏移。
		// 原理：如果所有 key 都在同一刻写入、同一 TTL，那么它们会在同一刻集体过期，
		// 那一刻所有请求都会打到数据库，DB 瞬间被打挂。
		// 加了随机偏移后，key 的过期时间被打散，流量被削峰。
		RedisCacheWriter.TtlFunction ttlFunction = (key, value) -> {
			long jitter = ThreadLocalRandom.current().nextLong(TTL_RANDOM_RANGE);
			return Duration.ofSeconds(BASE_TTL_SECONDS + jitter);
		};

		// Spring Data Redis 4 的 API 变化：
		// RedisCacheWriter 不再是"接口 + builder()"的写法，
		// 而是静态工厂方法 lockingRedisCacheWriter(...)。
		// locking 版本用分布式锁保证同一 key 的写入原子性（防缓存击穿）。
		RedisCacheWriter cacheWriter = RedisCacheWriter.lockingRedisCacheWriter(
				connectionFactory,
				Duration.ofSeconds(5),   // 锁等待超时
				ttlFunction,             // 每个 key 的 TTL 计算
				BatchStrategies.keys());

		return RedisCacheManager.builder(cacheWriter)
				.cacheDefaults(config)
				.build();
	}

}
