package com.coupon.coupon.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.BDDMockito.given;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.coupon.coupon.service.CouponReconciliationService;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/**
 * ShedLock이 대사 작업을 한 번에 한 서버만 실행하게 하는지 확인한다.
 * 스레드 두 개를 서버 두 대로 본다. 락은 Redis 키라서 같은 JVM의 스레드든 다른 서버든 똑같이 SET NX로 다툰다.
 * (실제 서버 2대 확인은 bench/reconcile-check.sh가 컨테이너 로그로 한다.)
 * 스케줄러를 켜되 cron을 "-"(실행 안 함)로 두고, 작업 메서드를 직접 부른다.
 */
@SpringBootTest(properties = {"coupon.reconcile.scheduled=true", "coupon.reconcile.cron=-"})
@ExtendWith(OutputCaptureExtension.class)
class ReconciliationSchedulerLockTest {

	private static final String LOCK_KEY = "job-lock:coupon:" + ReconciliationScheduler.LOCK_NAME;

	@Autowired
	private ReconciliationScheduler scheduler;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@MockitoBean
	private CouponReconciliationService couponReconciliationService;

	@BeforeEach
	@AfterEach
	void releaseLock() {
		redisTemplate.delete(LOCK_KEY);
	}

	@Test
	@DisplayName("두 서버가 같은 시각에 깨어나도 대사 작업은 한 서버만 실행한다")
	void onlyOneServerRuns(CapturedOutput output) throws InterruptedException {
		given(couponReconciliationService.reconcile()).willAnswer(invocation -> {
			Thread.sleep(500);
			return new CouponReconciliationService.Result(0, 0);
		});

		ExecutorService servers = Executors.newFixedThreadPool(2);
		CountDownLatch start = new CountDownLatch(1);
		for (int i = 0; i < 2; i++) {
			servers.submit(() -> {
				start.await();
				scheduler.reconcile();
				return null;
			});
		}
		start.countDown();
		servers.shutdown();
		assertThat(servers.awaitTermination(10, TimeUnit.SECONDS)).isTrue();

		verify(couponReconciliationService, times(1)).reconcile();
		assertThat(output).containsOnlyOnce("대사 작업 시작")
				.contains("Not executing '" + ReconciliationScheduler.LOCK_NAME + "'. It's locked.");
	}

	@Test
	@DisplayName("작업이 금방 끝나도 lockAtLeastFor(10초) 동안은 조금 늦게 깨어난 서버가 다시 실행하지 않는다")
	void lockAtLeastForBlocksLateServer() {
		given(couponReconciliationService.reconcile()).willReturn(new CouponReconciliationService.Result(0, 0));

		scheduler.reconcile();
		// 시계가 조금 늦은 서버: 첫 서버의 작업이 끝난 뒤에 깨어난다.
		scheduler.reconcile();

		verify(couponReconciliationService, times(1)).reconcile();
		// 락은 작업이 끝난 뒤에도 남아 있고, 시작 후 10초가 되면 풀린다.
		Long ttlMillis = redisTemplate.getExpire(LOCK_KEY, TimeUnit.MILLISECONDS);
		assertThat(ttlMillis).isBetween(1L, Duration.ofSeconds(10).toMillis());
	}

	@Test
	@DisplayName("락을 쥔 서버가 죽어 락을 못 풀어도, 유지 시간이 지나 키가 사라지면 다른 서버가 실행한다")
	void expiredLockOfDeadServerIsTakenOver() throws InterruptedException {
		given(couponReconciliationService.reconcile()).willReturn(new CouponReconciliationService.Result(0, 0));
		// 죽은 서버가 남긴 락. 실제로는 lockAtMostFor(50초) 뒤에 사라지지만 테스트에서는 1초로 둔다.
		redisTemplate.opsForValue().set(LOCK_KEY, "dead-server", Duration.ofSeconds(1));

		scheduler.reconcile();
		verify(couponReconciliationService, times(0)).reconcile();

		Thread.sleep(1_200);
		scheduler.reconcile();
		verify(couponReconciliationService, times(1)).reconcile();
	}
}
