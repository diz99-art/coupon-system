package com.coupon.coupon.config;

import com.coupon.coupon.service.CouponReconciliationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import net.javacrumbs.shedlock.spring.annotation.SchedulerLock;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/**
 * 대사 작업을 정해진 시각마다 돌린다. 테스트에서는 꺼 두고 대사 서비스를 직접 부른다.
 * cron이라 서버가 몇 대든 모두 같은 시각(매분 0초)에 깨어나고, ShedLock 락을 잡은 한 대만 실제로 실행한다.
 * - lockAtMostFor: 실행 중인 서버가 죽어 락을 못 풀어도 이 시간이 지나면 락이 풀린다. 다음 실행(1분 뒤)보다 짧아야 한다.
 * - lockAtLeastFor: 작업이 빨리 끝나도 이 시간까지는 락을 쥐고 있는다. 시계가 조금 늦은 서버가 끝난 뒤에 깨어나 또 실행하는 것을 막는다.
 */
@Slf4j
@Component
@ConditionalOnProperty(name = "coupon.reconcile.scheduled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReconciliationScheduler {

	public static final String LOCK_NAME = "couponReconciliation";

	private final CouponReconciliationService couponReconciliationService;

	@Scheduled(cron = "${coupon.reconcile.cron}")
	@SchedulerLock(name = LOCK_NAME,
			lockAtMostFor = "${coupon.reconcile.lock-at-most-for}",
			lockAtLeastFor = "${coupon.reconcile.lock-at-least-for}")
	public void reconcile() {
		long start = System.currentTimeMillis();
		log.info("대사 작업 시작");
		CouponReconciliationService.Result result = couponReconciliationService.reconcile();
		log.info("대사 작업 끝: {}ms, DB 저장 {}건, Redis 복구 {}건",
				System.currentTimeMillis() - start, result.savedToDb(), result.restoredToRedis());
	}
}
