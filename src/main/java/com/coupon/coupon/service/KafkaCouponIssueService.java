package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.exception.CouponAlreadyIssuedException;
import com.coupon.coupon.domain.exception.CouponIssueBusyException;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.domain.exception.CouponNotInIssuePeriodException;
import com.coupon.coupon.domain.exception.CouponSoldOutException;
import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.messaging.CouponIssueProducer;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRedisRepository.IssueResult;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 4단계: Redis로 발급 여부를 정하고, DB 저장은 Kafka 메시지로 넘긴 뒤 바로 응답한다.
 * 응답 시점에는 DB에 아직 기록이 없을 수 있다. 컨슈머(CouponIssueConsumer)가 곧 저장한다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaCouponIssueService {

	private final CouponRepository couponRepository;
	private final CouponIssueRedisRepository couponIssueRedisRepository;
	private final CouponIssueProducer couponIssueProducer;

	public CouponIssueMessage issue(Long couponId, Long userId) {
		// Redis score(밀리초)와 DB 값이 정확히 같도록 밀리초까지만 쓴다.
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

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

		CouponIssueMessage message = new CouponIssueMessage(couponId, userId, now);
		try {
			couponIssueProducer.send(message);
		} catch (Exception e) {
			// 메시지를 못 보냈으면 Redis에서 차지한 자리를 돌려놓고 실패로 응답한다.
			// 시간 초과처럼 실제로는 전달됐을 수도 있는 경우는 대사 작업이 DB 기준으로 Redis를 다시 맞춘다.
			log.warn("발급 메시지 전송 실패, Redis 되돌림: couponId={}, userId={}", couponId, userId, e);
			couponIssueRedisRepository.cancel(couponId, userId);
			throw new CouponIssueBusyException();
		}
		return message;
	}
}
