package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.exception.CouponAlreadyIssuedException;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 비관적 락으로 발급. 쿠폰 행을 SELECT ... FOR UPDATE로 잠근 채 확인하고 수정한다.
 * 같은 쿠폰을 노리는 다른 트랜잭션은 커밋될 때까지 줄을 서서 기다린다.
 */
@Service
@RequiredArgsConstructor
public class PessimisticLockCouponIssueService implements CouponIssuer {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	@Transactional
	public CouponIssue issue(Long couponId, Long userId) {
		LocalDateTime now = LocalDateTime.now();

		Coupon coupon = couponRepository.findByIdForUpdate(couponId)
				.orElseThrow(() -> new CouponNotFoundException());
		coupon.validateIssuable(now);
		if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
			throw new CouponAlreadyIssuedException();
		}

		coupon.increaseIssuedQuantity();
		return couponIssueRepository.save(new CouponIssue(couponId, userId, now));
	}
}
