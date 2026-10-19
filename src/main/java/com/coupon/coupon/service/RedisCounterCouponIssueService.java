package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.CouponIssueException;
import com.coupon.coupon.domain.CouponIssueException.ErrorCode;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Service;

/**
 * Redis SET(받은 유저)과 INCR(발급 수량)로 발급 여부를 정하고, 통과한 요청만 DB에 발급 기록을 저장한다.
 * 확인과 증가를 Lua 스크립트 하나로 묶어 Redis 안에서 원자적으로 실행한다.
 * 발급 수량의 기준은 Redis 카운터다. DB의 Coupon.issuedQuantity는 이 방법에서 갱신하지 않는다 (4단계에서 맞춘다).
 */
@Service
@RequiredArgsConstructor
public class RedisCounterCouponIssueService implements CouponIssuer {

	static final String ISSUED_USERS_KEY = "coupon:%d:users";
	static final String ISSUED_COUNT_KEY = "coupon:%d:count";

	private static final long OK = 1;
	private static final long ALREADY_ISSUED = 2;
	private static final long SOLD_OUT = 3;

	/**
	 * KEYS[1] 받은 유저 SET, KEYS[2] 발급 수량, ARGV[1] 유저 id, ARGV[2] 총 수량.
	 * Redis는 스크립트 하나를 실행하는 동안 다른 명령을 끼워 넣지 않는다.
	 */
	private static final RedisScript<Long> ISSUE_SCRIPT = RedisScript.of("""
			if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
				return 2
			end
			local count = tonumber(redis.call('GET', KEYS[2]) or '0')
			if count >= tonumber(ARGV[2]) then
				return 3
			end
			redis.call('INCR', KEYS[2])
			redis.call('SADD', KEYS[1], ARGV[1])
			return 1
			""", Long.class);

	private final StringRedisTemplate redisTemplate;
	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	public CouponIssue issue(Long couponId, Long userId) {
		LocalDateTime now = LocalDateTime.now();

		// 기간과 총 수량을 알기 위해 쿠폰을 읽는다. 락을 걸지 않으니 동시에 읽어도 줄을 서지 않는다.
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CouponIssueException(ErrorCode.COUPON_NOT_FOUND));
		if (!coupon.isInIssuePeriod(now)) {
			throw new CouponIssueException(ErrorCode.NOT_IN_ISSUE_PERIOD);
		}

		Long result = redisTemplate.execute(ISSUE_SCRIPT,
				List.of(ISSUED_USERS_KEY.formatted(couponId), ISSUED_COUNT_KEY.formatted(couponId)),
				String.valueOf(userId), String.valueOf(coupon.getTotalQuantity()));
		if (result != null && result == ALREADY_ISSUED) {
			throw new CouponIssueException(ErrorCode.ALREADY_ISSUED);
		}
		if (result == null || result != OK) {
			throw new CouponIssueException(ErrorCode.SOLD_OUT);
		}

		// Redis에서 자리를 얻은 요청만 DB에 기록한다. 이 저장이 실패하면 Redis와 DB가 어긋난다 (4단계에서 해결).
		return couponIssueRepository.save(new CouponIssue(couponId, userId, now));
	}
}
