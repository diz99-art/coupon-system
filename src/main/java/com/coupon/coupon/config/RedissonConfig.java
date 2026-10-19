package com.coupon.coupon.config;

import org.redisson.Redisson;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.util.StringUtils;

/** 분산 락용 Redisson 클라이언트. Redis 주소, 비밀번호, TLS 여부는 spring.data.redis 설정을 그대로 쓴다. */
@Configuration
public class RedissonConfig {

	@Bean(destroyMethod = "shutdown")
	public RedissonClient redissonClient(
			@Value("${spring.data.redis.host}") String host,
			@Value("${spring.data.redis.port}") int port,
			@Value("${spring.data.redis.password:}") String password,
			@Value("${spring.data.redis.ssl.enabled:false}") boolean ssl) {
		Config config = new Config();
		if (StringUtils.hasText(password)) {
			config.setPassword(password);
		}
		config.useSingleServer().setAddress((ssl ? "rediss://" : "redis://") + host + ":" + port);
		return Redisson.create(config);
	}
}
