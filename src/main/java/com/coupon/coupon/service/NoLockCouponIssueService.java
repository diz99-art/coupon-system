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
 * 1단계: 동시성 처리 없는 발급.
 * 조회 → 확인 → 수정 사이에 다른 요청이 끼어들 수 있어서 동시에 요청이 몰리면 초과 발급된다.
 * Coupon에 @Version이 붙은 뒤에도 경쟁 조건이 그대로 보이도록, 수량은 버전 확인 없는 UPDATE로 덮어쓴다.
 */
@Service
@RequiredArgsConstructor
public class NoLockCouponIssueService implements CouponIssuer {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	@Transactional
	public CouponIssue issue(Long couponId, Long userId) {
		LocalDateTime now = LocalDateTime.now();

		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CouponNotFoundException());
		coupon.validateIssuable(now);
		if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
			throw new CouponAlreadyIssuedException();
		}

		couponRepository.overwriteIssuedQuantity(couponId, coupon.getIssuedQuantity() + 1);
		return couponIssueRepository.save(new CouponIssue(couponId, userId, now));
	}
}
