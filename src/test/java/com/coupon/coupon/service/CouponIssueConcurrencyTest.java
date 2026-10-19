package com.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;

/**
 * 1단계: 동시성 처리가 없는 발급 로직에 1,000명이 동시에 요청하면 100장보다 많이 발급되는 것을 확인한다.
 * 문제를 보여주는 테스트라서 "초과 발급된다"를 검증한다. 2단계에서 해결한 뒤에는 정확히 100장인지 검증하도록 바꾼다.
 */
@SpringBootTest
class CouponIssueConcurrencyTest {

	private static final int TOTAL_QUANTITY = 100;
	private static final int USER_COUNT = 1_000;
	private static final int THREAD_COUNT = 32;

	@Autowired
	private CouponIssueService couponIssueService;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@BeforeEach
	void setUp() {
		couponIssueRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
	}

	@Test
	@DisplayName("동시성 처리 없이 1,000명이 동시에 요청하면 100장 쿠폰이 초과 발급된다")
	void overIssueWithoutConcurrencyControl() throws InterruptedException {
		LocalDateTime now = LocalDateTime.now();
		Coupon coupon = couponRepository.save(
				new Coupon("선착순 100장 쿠폰", TOTAL_QUANTITY, now.minusHours(1), now.plusHours(1)));

		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(USER_COUNT);
		AtomicInteger successCount = new AtomicInteger();
		AtomicInteger failCount = new AtomicInteger();

		for (int i = 1; i <= USER_COUNT; i++) {
			long userId = i;
			executor.submit(() -> {
				try {
					// 모든 스레드가 준비된 뒤 출발 신호에 맞춰 한꺼번에 요청한다.
					ready.countDown();
					start.await();
					couponIssueService.issue(coupon.getId(), userId);
					successCount.incrementAndGet();
				} catch (Exception e) {
					failCount.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}
		ready.await();
		start.countDown();
		done.await(60, TimeUnit.SECONDS);
		executor.shutdown();

		Coupon result = couponRepository.findById(coupon.getId()).orElseThrow();
		long issueRows = couponIssueRepository.countByCouponId(coupon.getId());

		System.out.printf("%n===== 1단계 결과 =====%n");
		System.out.printf("총 수량: %d장, 요청: %d명%n", TOTAL_QUANTITY, USER_COUNT);
		System.out.printf("발급 성공 응답: %d건, 실패 응답: %d건%n", successCount.get(), failCount.get());
		System.out.printf("CouponIssue 저장 건수(실제 발급): %d건%n", issueRows);
		System.out.printf("Coupon.issuedQuantity 값: %d%n", result.getIssuedQuantity());
		System.out.printf("======================%n%n");

		assertThat(issueRows).isGreaterThan(TOTAL_QUANTITY);
	}
}
