package com.coupon.coupon;

import org.springframework.data.redis.core.StringRedisTemplate;

public final class RedisTestSupport {

	private RedisTestSupport() {
	}

	/** 테스트를 다시 실행하면 쿠폰 id가 1부터 다시 시작하므로 지난번 쿠폰 키를 지운다. */
	public static void clearCouponKeys(StringRedisTemplate redisTemplate) {
		var keys = redisTemplate.keys("coupon:*");
		if (keys != null && !keys.isEmpty()) {
			redisTemplate.delete(keys);
		}
	}
}
