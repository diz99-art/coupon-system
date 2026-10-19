package com.coupon.coupon;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Spring과 Redisson이 docker의 Redis에 실제로 붙는지 확인한다. */
@SpringBootTest
class RedisConnectionTest {

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private RedissonClient redissonClient;

	@Test
	void springDataRedisConnects() {
		String pong = redisTemplate.execute((org.springframework.data.redis.core.RedisCallback<String>) c -> c.ping());
		assertThat(pong).isEqualTo("PONG");

		redisTemplate.opsForValue().set("test:connection", "ok", Duration.ofSeconds(10));
		assertThat(redisTemplate.opsForValue().get("test:connection")).isEqualTo("ok");
		redisTemplate.delete("test:connection");
	}

	@Test
	void redissonConnects() {
		var lock = redissonClient.getLock("test:lock");
		assertThat(lock.tryLock()).isTrue();
		lock.unlock();
	}
}
