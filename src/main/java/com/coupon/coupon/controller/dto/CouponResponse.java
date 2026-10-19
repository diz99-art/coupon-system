package com.coupon.coupon.controller.dto;

import com.coupon.coupon.domain.Coupon;
import java.time.LocalDateTime;

/** 발급 수량과 남은 수량은 Redis 카운터 기준이다. */
public record CouponResponse(
		Long id,
		String name,
		int totalQuantity,
		long issuedQuantity,
		long remainingQuantity,
		LocalDateTime issueStartAt,
		LocalDateTime issueEndAt) {

	public static CouponResponse of(Coupon coupon, long issuedQuantity) {
		long issued = Math.min(issuedQuantity, coupon.getTotalQuantity());
		return new CouponResponse(coupon.getId(), coupon.getName(), coupon.getTotalQuantity(), issued,
				coupon.getTotalQuantity() - issued, coupon.getIssueStartAt(), coupon.getIssueEndAt());
	}
}
