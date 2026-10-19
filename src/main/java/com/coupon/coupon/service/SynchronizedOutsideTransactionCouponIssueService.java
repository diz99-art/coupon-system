package com.coupon.coupon.service;

import com.coupon.coupon.domain.CouponIssue;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/**
 * synchronized를 트랜잭션 바깥에 건 발급. 안쪽 트랜잭션(락 없는 발급)이 커밋까지 끝난 뒤에 락이 풀린다.
 * 서버 한 대에서는 정확하지만, synchronized는 JVM 하나 안에서만 잠그므로 서버가 여러 대면 소용없다.
 * 정확도가 락에서만 나오도록 @Version 확인이 없는 NoLockCouponIssueService를 감싼다.
 */
@Service
@RequiredArgsConstructor
public class SynchronizedOutsideTransactionCouponIssueService implements CouponIssuer {

	private final NoLockCouponIssueService noLockCouponIssueService;

	@Override
	public synchronized CouponIssue issue(Long couponId, Long userId) {
		return noLockCouponIssueService.issue(couponId, userId);
	}
}
