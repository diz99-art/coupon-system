package com.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coupon.coupon.RedisTestSupport;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.Set;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;

@SpringBootTest
class CouponReconciliationTest {

	@Autowired
	private CouponReconciliationService couponReconciliationService;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@Autowired
	private CouponIssueRedisRepository couponIssueRedisRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@BeforeEach
	void setUp() {
		couponIssueRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		RedisTestSupport.clearCouponKeys(redisTemplate);
	}

	@Test
	@DisplayName("Redis에만 있는 발급은 DB에 저장하고, DB에만 있는 발급은 Redis에 다시 넣는다")
	void reconcileBothDirections() {
		LocalDateTime now = LocalDateTime.now();
		Coupon coupon = couponRepository.save(new Coupon("대사 쿠폰", 100, now.minusHours(1), now.plusHours(1)));
		Long couponId = coupon.getId();

		// 유저 1~5: Redis 판정 통과. 그중 1~3만 DB에 저장됨 (4, 5는 메시지가 DLT로 갔다고 가정)
		for (long userId = 1; userId <= 5; userId++) {
			couponIssueRedisRepository.tryIssue(couponId, userId, coupon.getTotalQuantity());
		}
		for (long userId = 1; userId <= 3; userId++) {
			couponIssueRepository.save(new CouponIssue(couponId, userId, now));
		}
		// 유저 6: DB에는 있는데 Redis에는 없음 (Redis 데이터가 사라졌다고 가정)
		couponIssueRepository.save(new CouponIssue(couponId, 6L, now));

		printState("대사 전", couponId);
		CouponReconciliationService.Result result = couponReconciliationService.reconcile();
		printState("대사 후", couponId);

		assertThat(result.savedToDb()).isEqualTo(2);
		assertThat(result.restoredToRedis()).isEqualTo(1);
		assertThat(couponIssueRepository.findUserIdsByCouponId(couponId)).isEqualTo(Set.of(1L, 2L, 3L, 4L, 5L, 6L));
		assertThat(couponIssueRedisRepository.issuedUserIds(couponId)).isEqualTo(Set.of(1L, 2L, 3L, 4L, 5L, 6L));
		assertThat(couponIssueRedisRepository.issuedCount(couponId)).isEqualTo(6);

		// 두 번 돌려도 더 바뀌는 것이 없다.
		assertThat(couponReconciliationService.reconcile()).isEqualTo(new CouponReconciliationService.Result(0, 0));
	}

	private void printState(String label, Long couponId) {
		System.out.printf("[대사] %s: Redis 유저 %s (카운터 %d), DB 유저 %s%n", label,
				couponIssueRedisRepository.issuedUserIds(couponId), couponIssueRedisRepository.issuedCount(couponId),
				couponIssueRepository.findUserIdsByCouponId(couponId));
	}
}
