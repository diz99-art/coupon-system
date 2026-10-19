package com.coupon.coupon.config;

import com.coupon.coupon.service.CouponReconciliationService;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/** 대사 작업을 일정 간격으로 돌린다. 테스트에서는 꺼 두고 대사 서비스를 직접 부른다. */
@Configuration
@EnableScheduling
@ConditionalOnProperty(name = "coupon.reconcile.scheduled", havingValue = "true", matchIfMissing = true)
@RequiredArgsConstructor
public class ReconciliationScheduler {

	private final CouponReconciliationService couponReconciliationService;

	@Scheduled(fixedDelayString = "${coupon.reconcile.interval-ms}", initialDelayString = "${coupon.reconcile.interval-ms}")
	public void reconcile() {
		couponReconciliationService.reconcile();
	}
}
