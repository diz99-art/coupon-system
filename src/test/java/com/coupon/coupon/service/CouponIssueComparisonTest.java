package com.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coupon.coupon.RedisTestSupport;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.ApplicationContext;
import org.springframework.data.redis.core.StringRedisTemplate;

/**
 * 2·3단계: 같은 1,000명 동시 요청을 발급 방법마다 3번씩 돌려 정확도와 소요 시간을 비교한다.
 * SQL 출력은 콘솔 쓰기가 시간을 잡아먹으므로 끈다.
 */
@SpringBootTest(properties = "spring.jpa.show-sql=false")
class CouponIssueComparisonTest {

	private static final int TOTAL_QUANTITY = 100;
	private static final int USER_COUNT = 1_000;
	private static final int THREAD_COUNT = 32;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@Autowired
	private OptimisticLockCouponIssueService optimisticLockService;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private CouponIssueRedisRepository couponIssueRedisRepository;

	/** 결과 확인 방법: NONE은 기록만, EXACT는 발급 기록과 issuedQuantity 모두 100, ROWS_ONLY는 발급 기록만 100 */
	enum Expect {
		NONE, EXACT, ROWS_ONLY
	}

	static Stream<Arguments> issuers() {
		return Stream.of(
				Arguments.of(NoLockCouponIssueService.class, Expect.NONE),
				Arguments.of(SynchronizedCouponIssueService.class, Expect.NONE),
				Arguments.of(SynchronizedOutsideTransactionCouponIssueService.class, Expect.EXACT),
				Arguments.of(PessimisticLockCouponIssueService.class, Expect.EXACT),
				Arguments.of(OptimisticLockCouponIssueService.class, Expect.EXACT),
				Arguments.of(RedissonLockCouponIssueService.class, Expect.EXACT),
				// Redis 카운터 방법은 DB의 issuedQuantity를 갱신하지 않는다. 대신 Redis 카운터가 100인지 본다.
				Arguments.of(RedisCounterCouponIssueService.class, Expect.ROWS_ONLY));
	}

	@BeforeEach
	void setUp() {
		couponIssueRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		RedisTestSupport.clearCouponKeys(redisTemplate);
	}

	@ParameterizedTest(name = "{0}")
	@MethodSource("issuers")
	void compare(Class<? extends CouponIssuer> issuerType, Expect expect) throws InterruptedException {
		CouponIssuer issuer = context.getBean(issuerType);
		for (int round = 1; round <= 3; round++) {
			Result result = runOnce(issuer);
			System.out.printf("[비교] %s %d회차: 발급 %d건, issuedQuantity %d, Redis 카운터 %d, 소요 %dms, 재시도 %d회%n",
					issuerType.getSimpleName(), round, result.issueRows, result.issuedQuantity,
					result.redisCount, result.elapsedMillis, result.retries);
			if (expect != Expect.NONE) {
				assertThat(result.issueRows).isEqualTo(TOTAL_QUANTITY);
			}
			if (expect == Expect.EXACT) {
				assertThat(result.issuedQuantity).isEqualTo(TOTAL_QUANTITY);
			}
			if (expect == Expect.ROWS_ONLY) {
				assertThat(result.redisCount).isEqualTo(TOTAL_QUANTITY);
			}
		}
	}

	private Result runOnce(CouponIssuer issuer) throws InterruptedException {
		LocalDateTime now = LocalDateTime.now();
		Coupon coupon = couponRepository.save(
				new Coupon("선착순 100장 쿠폰", TOTAL_QUANTITY, now.minusHours(1), now.plusHours(1)));
		optimisticLockService.resetRetryCount();

		ExecutorService executor = Executors.newFixedThreadPool(THREAD_COUNT);
		CountDownLatch ready = new CountDownLatch(THREAD_COUNT);
		CountDownLatch start = new CountDownLatch(1);
		CountDownLatch done = new CountDownLatch(USER_COUNT);
		AtomicInteger unexpectedErrors = new AtomicInteger();

		for (int i = 1; i <= USER_COUNT; i++) {
			long userId = i;
			executor.submit(() -> {
				try {
					ready.countDown();
					start.await();
					issuer.issue(coupon.getId(), userId);
				} catch (com.coupon.coupon.domain.CouponIssueException expected) {
					// 매진, 기간 밖 등 정상적인 거절
				} catch (Exception e) {
					unexpectedErrors.incrementAndGet();
				} finally {
					done.countDown();
				}
			});
		}
		ready.await();
		long startedAt = System.nanoTime();
		start.countDown();
		done.await(120, TimeUnit.SECONDS);
		long elapsedMillis = (System.nanoTime() - startedAt) / 1_000_000;
		executor.shutdown();

		Coupon saved = couponRepository.findById(coupon.getId()).orElseThrow();
		long issueRows = couponIssueRepository.countByCouponId(coupon.getId());
		if (unexpectedErrors.get() > 0) {
			System.out.printf("[비교] 예상하지 못한 예외 %d건%n", unexpectedErrors.get());
		}
		long redisCount = couponIssueRedisRepository.issuedCount(coupon.getId());
		return new Result(issueRows, saved.getIssuedQuantity(), redisCount, elapsedMillis,
				optimisticLockService.getRetryCount());
	}

	private record Result(long issueRows, int issuedQuantity, long redisCount, long elapsedMillis, long retries) {
	}
}
