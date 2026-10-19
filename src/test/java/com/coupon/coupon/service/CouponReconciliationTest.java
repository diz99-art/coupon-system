package com.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coupon.coupon.IntegrationTest;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

@SpringBootTest
class CouponReconciliationTest extends IntegrationTest {

	@Autowired
	private CouponReconciliationService couponReconciliationService;

	@Test
	@DisplayName("Redis에만 있는 발급은 원래 발급 시각으로 DB에 저장하고, DB에만 있는 발급은 Redis에 다시 넣는다")
	void reconcileBothDirections() {
		// 대사 유예 시간(30초)보다 오래된 발급으로 만든다.
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS).minusHours(1);
		Coupon coupon = couponRepository.save(new Coupon("대사 쿠폰", 100, now.minusHours(1), now.plusHours(2)));
		Long couponId = coupon.getId();

		// 유저 1~5: 1분 간격으로 Redis 판정 통과. 그중 1~3만 DB에 저장됨 (4, 5는 메시지가 DLT로 갔다고 가정)
		for (long userId = 1; userId <= 5; userId++) {
			couponIssueRedisRepository.tryIssue(couponId, userId, coupon.getTotalQuantity(), issuedAt(now, userId));
		}
		for (long userId = 1; userId <= 3; userId++) {
			couponIssueRepository.save(new CouponIssue(couponId, userId, issuedAt(now, userId)));
		}
		// 유저 6: DB에는 있는데 Redis에는 없음 (Redis 데이터가 사라졌다고 가정)
		couponIssueRepository.save(new CouponIssue(couponId, 6L, issuedAt(now, 6L)));

		printState("대사 전", couponId);
		CouponReconciliationService.Result result = couponReconciliationService.reconcile();
		printState("대사 후", couponId);

		assertThat(result.savedToDb()).isEqualTo(2);
		assertThat(result.restoredToRedis()).isEqualTo(1);
		Map<Long, LocalDateTime> expected = Set.of(1L, 2L, 3L, 4L, 5L, 6L).stream()
				.collect(Collectors.toMap(userId -> userId, userId -> issuedAt(now, userId)));
		// 양쪽 모두 같은 유저를, 원래 발급 시각 그대로 가진다.
		assertThat(dbIssues(couponId)).isEqualTo(expected);
		assertThat(couponIssueRedisRepository.issuedUsers(couponId)).isEqualTo(expected);
		assertThat(couponIssueRedisRepository.issuedCount(couponId)).isEqualTo(6);

		// 두 번 돌려도 더 바뀌는 것이 없다.
		assertThat(couponReconciliationService.reconcile()).isEqualTo(new CouponReconciliationService.Result(0, 0));
	}

	@Test
	@DisplayName("방금 Redis에 들어온 발급은 아직 저장 중일 수 있으므로 DB에 채우지 않는다")
	void skipRecentIssues() {
		LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
		Coupon coupon = couponRepository.save(new Coupon("대사 쿠폰", 100, now.minusHours(1), now.plusHours(1)));
		couponIssueRedisRepository.tryIssue(coupon.getId(), 1L, coupon.getTotalQuantity(), now.minusMinutes(5));
		couponIssueRedisRepository.tryIssue(coupon.getId(), 2L, coupon.getTotalQuantity(), now);

		CouponReconciliationService.Result result = couponReconciliationService.reconcile();

		assertThat(result.savedToDb()).isEqualTo(1);
		assertThat(dbIssues(coupon.getId())).containsOnlyKeys(1L);
	}

	/** 유저마다 다른 발급 시각: 기준 시각 + 유저 id분 */
	private LocalDateTime issuedAt(LocalDateTime base, long userId) {
		return base.plusMinutes(userId);
	}

	private Map<Long, LocalDateTime> dbIssues(Long couponId) {
		return couponIssueRepository.findByCouponId(couponId).stream()
				.collect(Collectors.toMap(CouponIssue::getUserId, CouponIssue::getIssuedAt));
	}

	private void printState(String label, Long couponId) {
		System.out.printf("[대사] %s: Redis %s (카운터 %d), DB %s%n", label,
				couponIssueRedisRepository.issuedUsers(couponId), couponIssueRedisRepository.issuedCount(couponId),
				dbIssues(couponId));
	}
}
