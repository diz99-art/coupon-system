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
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * 대사(reconciliation): Redis의 발급 유저 목록과 DB의 발급 기록을 비교해 빠진 쪽을 채운다.
 * - Redis에만 있음: Kafka 메시지가 DLT로 갔거나 유실됨 → Redis에 남은 원래 발급 시각으로 DB에 저장
 * - DB에만 있음: Redis 데이터가 사라졌거나 전송 시간 초과 후 Redis를 되돌림 → DB의 발급 시각으로 Redis에 다시 넣음
 * 발급된 지 graceMillis가 지나지 않은 Redis 기록은 아직 저장 중(Kafka 메시지 처리 중, 동기 저장 직전)일 수 있으므로 건너뛴다.
 * 건너뛰지 않으면 대사 작업이 먼저 저장해 버려서, 원래 요청의 저장이 중복으로 실패하고 "이미 발급" 응답이 나간다.
 */
@Slf4j
@Service
public class CouponReconciliationService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponIssueRedisRepository couponIssueRedisRepository;
	private final CouponIssueSaver couponIssueSaver;
	private final long graceMillis;

	public CouponReconciliationService(CouponRepository couponRepository, CouponIssueRepository couponIssueRepository,
			CouponIssueRedisRepository couponIssueRedisRepository, CouponIssueSaver couponIssueSaver,
			@Value("${coupon.reconcile.grace-ms}") long graceMillis) {
		this.couponRepository = couponRepository;
		this.couponIssueRepository = couponIssueRepository;
		this.couponIssueRedisRepository = couponIssueRedisRepository;
		this.couponIssueSaver = couponIssueSaver;
		this.graceMillis = graceMillis;
	}

	public Result reconcile() {
		int savedToDb = 0;
		int restoredToRedis = 0;
		LocalDateTime settledBefore = LocalDateTime.now().minusNanos(graceMillis * 1_000_000);
		// 쿠폰이 많아지면 발급 기간이 끝난 지 얼마 안 된 쿠폰만 고르도록 바꾼다.
		for (Coupon coupon : couponRepository.findAll()) {
			Map<Long, LocalDateTime> inRedis = couponIssueRedisRepository.issuedUsers(coupon.getId());
			Map<Long, LocalDateTime> inDb = couponIssueRepository.findByCouponId(coupon.getId()).stream()
					.collect(Collectors.toMap(CouponIssue::getUserId, CouponIssue::getIssuedAt));

			for (Map.Entry<Long, LocalDateTime> issue : inRedis.entrySet()) {
				if (!inDb.containsKey(issue.getKey())
						&& issue.getValue().isBefore(settledBefore)
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
