package com.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.coupon.coupon.IntegrationTest;
import com.coupon.coupon.KafkaTestSupport;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.exception.CouponException;
import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BiConsumer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.test.annotation.DirtiesContext;

/**
 * 4단계: 1,000명 동시 요청에서 "API 응답이 모두 끝난 시간"과 "DB 저장이 모두 끝난 시간"을 따로 잰다.
 * 3단계 Redis 동기 저장 방식과 번갈아 3번씩 돌린다. docker의 실제 Kafka를 쓴다.
 * 쿠폰 100장(900명은 Redis에서 거절)과, DB 쓰기가 많은 경우를 보려고 1,000장(전원 발급) 두 가지로 잰다.
 * 개발 서버가 읽는 토픽과 섞이지 않게 측정 전용 토픽을 쓴다.
 * 지난번 측정 메시지는 읽지 않도록 컨슈머 그룹을 매번 새로 만들고 최신 위치(latest)부터 읽는다.
 */
@SpringBootTest(properties = {
		"spring.jpa.show-sql=false",
		"coupon.kafka.listener-auto-startup=true",
		"coupon.kafka.topic=coupon-issue-measure",
		"coupon.kafka.dead-letter-topic=coupon-issue-measure.DLT",
		"coupon.kafka.group-id=measure-${random.uuid}",
		"spring.kafka.consumer.auto-offset-reset=latest"})
@DirtiesContext
class KafkaAsyncMeasurementTest extends IntegrationTest {

	private static final int USER_COUNT = 1_000;
	private static final int THREAD_COUNT = 32;

	@Autowired
	private KafkaCouponIssueService kafkaCouponIssueService;

	@Autowired
	private RedisCounterCouponIssueService redisCounterCouponIssueService;

	@Autowired
	private KafkaListenerEndpointRegistry listenerRegistry;

	@BeforeEach
	void setUp() {
		KafkaTestSupport.waitForAssignment(listenerRegistry);
	}

	@Test
	void measureApiAndDbCompletion() throws InterruptedException {
		for (int totalQuantity : new int[] {100, 1_000}) {
			for (int round = 1; round <= 3; round++) {
				Result sync = runOnce(redisCounterCouponIssueService::issue, totalQuantity);
				Result async = runOnce(kafkaCouponIssueService::issue, totalQuantity);
				System.out.printf("[측정] %d장 %d회차 Redis 동기 저장: API %dms, DB 완료 %dms, 발급 %d건%n",
						totalQuantity, round, sync.apiMillis, sync.dbMillis, sync.issueRows);
				System.out.printf("[측정] %d장 %d회차 Redis + Kafka: API %dms, DB 완료 %dms, 발급 %d건%n",
						totalQuantity, round, async.apiMillis, async.dbMillis, async.issueRows);
				assertThat(sync.issueRows).isEqualTo(totalQuantity);
				assertThat(async.issueRows).isEqualTo(totalQuantity);
				assertThat(async.redisCount).isEqualTo(totalQuantity);
			}
		}
	}

	private Result runOnce(BiConsumer<Long, Long> issuer, int totalQuantity) throws InterruptedException {
		LocalDateTime now = LocalDateTime.now();
		Coupon coupon = couponRepository.save(
				new Coupon("선착순 쿠폰", totalQuantity, now.minusHours(1), now.plusHours(1)));

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
					issuer.accept(coupon.getId(), userId);
				} catch (CouponException expected) {
					// 매진 등 정상적인 거절
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
		long apiMillis = (System.nanoTime() - startedAt) / 1_000_000;
		executor.shutdown();

		// DB에 발급 기록이 모두 들어올 때까지 5ms마다 확인한다.
		long deadline = System.currentTimeMillis() + 30_000;
		while (couponIssueRepository.countByCouponId(coupon.getId()) < totalQuantity
				&& System.currentTimeMillis() < deadline) {
			Thread.sleep(5);
		}
		long dbMillis = (System.nanoTime() - startedAt) / 1_000_000;
		assertThat(unexpectedErrors.get()).isZero();

		return new Result(apiMillis, dbMillis, couponIssueRepository.countByCouponId(coupon.getId()),
				couponIssueRedisRepository.issuedCount(coupon.getId()));
	}

	private record Result(long apiMillis, long dbMillis, long issueRows, long redisCount) {
	}
}
