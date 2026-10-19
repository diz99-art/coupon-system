package com.coupon.coupon.repository;

import com.coupon.coupon.domain.Coupon;
import java.time.LocalDateTime;

/** 발급 판정에 필요한 쿠폰 정보. DB의 Coupon에서 만들어 Redis 해시(coupon:{id}:info)에 둔다. */
public record CouponInfo(Long couponId, int totalQuantity, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {

	public static CouponInfo from(Coupon coupon) {
		return new CouponInfo(coupon.getId(), coupon.getTotalQuantity(), coupon.getIssueStartAt(), coupon.getIssueEndAt());
	}
}
