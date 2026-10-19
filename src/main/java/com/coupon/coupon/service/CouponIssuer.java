package com.coupon.coupon.service;

import com.coupon.coupon.domain.CouponIssue;

/** 쿠폰 발급 방법. 동시성 처리 방식만 다른 구현을 같은 테스트로 비교한다. */
public interface CouponIssuer {

	CouponIssue issue(Long couponId, Long userId);
}
