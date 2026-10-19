package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.exception.CouponAlreadyIssuedException;
import com.coupon.coupon.domain.exception.CouponException;
import com.coupon.coupon.domain.exception.CouponIssueBusyException;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.domain.exception.CouponNotInIssuePeriodException;
import com.coupon.coupon.domain.exception.CouponSoldOutException;
import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.messaging.CouponIssueProducer;
import com.coupon.coupon.metrics.CouponMetrics;
import com.coupon.coupon.repository.CouponInfo;
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
 * 8단계: 쿠폰 정보(기간, 총 수량)도 Redis에서 확인해서, 발급 요청 1건이 DB를 쓰지 않고 Redis 명령 1번(Lua)으로 끝난다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class KafkaCouponIssueService {

	private final CouponRepository couponRepository;
	private final CouponIssueRedisRepository couponIssueRedisRepository;
	private final CouponIssueProducer couponIssueProducer;
	private final CouponMetrics couponMetrics;

	/** 발급하고, 결과(확정 또는 거절 사유)를 지표로 센다. */
	public CouponIssueMessage issue(Long couponId, Long userId) {
		try {
			CouponIssueMessage message = tryIssue(couponId, userId);
			couponMetrics.issued();
			return message;
		} catch (CouponException e) {
			couponMetrics.rejected(e.getErrorCode());
			throw e;
		}
	}

	private CouponIssueMessage tryIssue(Long couponId, Long userId) {
		// Redis score(밀리초)와 DB 값이 정확히 같도록 밀리초까지만 쓴다.
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);

		// 8단계: 기간, 수량, 중복을 Redis의 쿠폰 정보로 한 번에 판정한다. 쿠폰 정보가 Redis에 있으면 DB를 읽지 않는다.
		IssueResult result = couponIssueRedisRepository.tryIssue(couponId, userId, now);
		if (result == IssueResult.INFO_NOT_LOADED) {
			// 캐시 미스(Redis 데이터 유실, 개발 서버 초기화 등): DB에서 읽어 올리고 한 번 더 시도한다.
			loadCouponInfo(couponId);
			result = couponIssueRedisRepository.tryIssue(couponId, userId, now);
		}
		switch (result) {
			case OK -> { }
			case ALREADY_ISSUED -> throw new CouponAlreadyIssuedException();
			case SOLD_OUT -> throw new CouponSoldOutException();
			case NOT_IN_ISSUE_PERIOD -> throw new CouponNotInIssuePeriodException();
			// 올린 직후 지워진 경우(동시에 무효화)뿐이라 드물다. 잠시 뒤 다시 시도하게 한다.
			case INFO_NOT_LOADED -> throw new CouponIssueBusyException();
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

	private void loadCouponInfo(Long couponId) {
		Coupon coupon = couponRepository.findById(couponId).orElseThrow(CouponNotFoundException::new);
		couponIssueRedisRepository.loadInfo(CouponInfo.from(coupon));
	}
}
