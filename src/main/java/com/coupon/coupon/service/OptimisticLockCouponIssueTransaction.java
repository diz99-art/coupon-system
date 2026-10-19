package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.exception.CouponAlreadyIssuedException;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

/**
 * 낙관적 락 발급의 한 번의 시도. 락 없이 읽고, 커밋할 때
 * UPDATE ... WHERE id = ? AND version = ? 로 그사이 바뀌지 않았는지 확인한다.
 * 바뀌었으면 ObjectOptimisticLockingFailureException이 나고 트랜잭션 전체가 되돌려진다.
 */
@Component
@RequiredArgsConstructor
class OptimisticLockCouponIssueTransaction {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Transactional
	public CouponIssue issue(Long couponId, Long userId) {
		LocalDateTime now = LocalDateTime.now();

		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CouponNotFoundException());
		coupon.validateIssuable(now);
		if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
			throw new CouponAlreadyIssuedException();
		}

		coupon.increaseIssuedQuantity();
		return couponIssueRepository.save(new CouponIssue(couponId, userId, now));
	}
}
