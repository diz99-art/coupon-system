package com.coupon.coupon.domain.exception;

/** 총 수량을 모두 발급했다. */
public class CouponSoldOutException extends CouponException {

	public CouponSoldOutException() {
		super(ErrorCode.COUPON_SOLD_OUT);
	}
}
