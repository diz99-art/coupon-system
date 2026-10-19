package com.coupon.coupon.config;

import net.javacrumbs.shedlock.core.LockProvider;
import net.javacrumbs.shedlock.provider.redis.spring.RedisLockProvider;
import net.javacrumbs.shedlock.spring.annotation.EnableSchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * 스케줄 작업과 분산 락(ShedLock). 서버가 여러 대여도 @SchedulerLock이 붙은 작업은 한 번에 한 서버만 실행한다.
 * 락은 Redis 키 "job-lock:coupon:<작업 이름>"이다. 먼저 SET NX에 성공한 서버가 실행하고, 나머지는 건너뛴다.
 * 테스트에서는 coupon.reconcile.scheduled=false로 꺼 둔다.
 */
@Configuration
@EnableScheduling
@EnableSchedulerLock(defaultLockAtMostFor = "${coupon.reconcile.lock-at-most-for}")
@ConditionalOnProperty(name = "coupon.reconcile.scheduled", havingValue = "true", matchIfMissing = true)
public class SchedulingConfig {

	@Bean
	public LockProvider lockProvider(RedisConnectionFactory redisConnectionFactory) {
		return new RedisLockProvider.Builder(redisConnectionFactory).environment("coupon").build();
	}
}
