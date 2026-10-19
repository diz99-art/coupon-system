package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.CouponIssueException;
import com.coupon.coupon.domain.CouponIssueException.ErrorCode;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 쿠폰 발급. 1단계는 동시성 처리를 하지 않는다.
 * 조회 → 확인 → 수정 사이에 다른 요청이 끼어들 수 있어서 동시에 요청이 몰리면 초과 발급된다.
 */
@Service
@RequiredArgsConstructor
public class CouponIssueService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Transactional
	public CouponIssue issue(Long couponId, Long userId) {
		LocalDateTime now = LocalDateTime.now();

		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CouponIssueException(ErrorCode.COUPON_NOT_FOUND));
		if (!coupon.isInIssuePeriod(now)) {
			throw new CouponIssueException(ErrorCode.NOT_IN_ISSUE_PERIOD);
		}
		if (!coupon.hasRemainingQuantity()) {
			throw new CouponIssueException(ErrorCode.SOLD_OUT);
		}
		if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
			throw new CouponIssueException(ErrorCode.ALREADY_ISSUED);
		}

		coupon.increaseIssuedQuantity();
		return couponIssueRepository.save(new CouponIssue(couponId, userId, now));
	}
}
