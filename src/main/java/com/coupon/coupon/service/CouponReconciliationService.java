package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import com.coupon.coupon.domain.CouponIssue;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 대사(reconciliation): Redis의 발급 유저 목록과 DB의 발급 기록을 비교해 빠진 쪽을 채운다.
 * - Redis에만 있음: Kafka 메시지가 DLT로 갔거나 유실됨 → Redis에 남은 원래 발급 시각으로 DB에 저장
 * - DB에만 있음: Redis 데이터가 사라졌거나 전송 시간 초과 후 Redis를 되돌림 → DB의 발급 시각으로 Redis에 다시 넣음
 * 컨슈머가 아직 처리하지 않은 메시지와 겹쳐도 저장이 멱등이라 중복되지 않는다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponReconciliationService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponIssueRedisRepository couponIssueRedisRepository;
	private final CouponIssueSaver couponIssueSaver;

	public Result reconcile() {
		int savedToDb = 0;
		int restoredToRedis = 0;
		// 쿠폰이 많아지면 발급 기간이 끝난 지 얼마 안 된 쿠폰만 고르도록 바꾼다.
		for (Coupon coupon : couponRepository.findAll()) {
			Map<Long, LocalDateTime> inRedis = couponIssueRedisRepository.issuedUsers(coupon.getId());
			Map<Long, LocalDateTime> inDb = couponIssueRepository.findByCouponId(coupon.getId()).stream()
					.collect(Collectors.toMap(CouponIssue::getUserId, CouponIssue::getIssuedAt));

			for (Map.Entry<Long, LocalDateTime> issue : inRedis.entrySet()) {
				if (!inDb.containsKey(issue.getKey())
						&& couponIssueSaver.save(new CouponIssueMessage(coupon.getId(), issue.getKey(), issue.getValue()))) {
					savedToDb++;
				}
			}

			Map<Long, LocalDateTime> missingInRedis = new HashMap<>(inDb);
			missingInRedis.keySet().removeAll(inRedis.keySet());
			couponIssueRedisRepository.restore(coupon.getId(), missingInRedis);
			restoredToRedis += missingInRedis.size();
		}
		if (savedToDb > 0 || restoredToRedis > 0) {
			log.warn("대사 보정: DB에 저장 {}건, Redis에 복구 {}건", savedToDb, restoredToRedis);
		}
		return new Result(savedToDb, restoredToRedis);
	}

	public record Result(int savedToDb, int restoredToRedis) {
	}
}
