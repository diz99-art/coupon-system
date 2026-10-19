package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.exception.CouponAlreadyIssuedException;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.domain.exception.CouponNotInIssuePeriodException;
import com.coupon.coupon.domain.exception.CouponSoldOutException;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRedisRepository.IssueResult;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * 3단계: Redis SET(받은 유저)과 INCR(발급 수량)로 발급 여부를 정하고, 통과한 요청만 DB에 바로(동기) 저장한다.
 * 확인과 증가는 Lua 스크립트 하나로 묶어 Redis 안에서 원자적으로 실행한다 (CouponIssueRedisRepository).
 * 발급 수량의 기준은 Redis 카운터다. DB의 Coupon.issuedQuantity는 이 방법에서 갱신하지 않는다.
 */
@Service
@RequiredArgsConstructor
public class RedisCounterCouponIssueService implements CouponIssuer {

	private final CouponIssueRedisRepository couponIssueRedisRepository;
	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	public CouponIssue issue(Long couponId, Long userId) {
		// Redis score(밀리초)와 DB 값이 정확히 같도록 밀리초까지만 쓴다.
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

		// 기간과 총 수량을 알기 위해 쿠폰을 읽는다. 락을 걸지 않으니 동시에 읽어도 줄을 서지 않는다.
		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CouponNotFoundException());
		if (!coupon.isInIssuePeriod(now)) {
			throw new CouponNotInIssuePeriodException();
		}

		IssueResult result = couponIssueRedisRepository.tryIssue(couponId, userId, coupon.getTotalQuantity(), now);
		if (result == IssueResult.ALREADY_ISSUED) {
			throw new CouponAlreadyIssuedException();
		}
		if (result == IssueResult.SOLD_OUT) {
			throw new CouponSoldOutException();
		}

		// Redis에서 자리를 얻은 요청만 DB에 기록한다. 이 저장이 실패하면 Redis와 DB가 어긋난다 (4단계에서 대사 작업으로 보정).
		return couponIssueRepository.save(new CouponIssue(couponId, userId, now));
	}
}
