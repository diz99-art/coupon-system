package com.coupon.coupon.domain.exception;

/** 쿠폰이 없다. */
public class CouponNotFoundException extends CouponException {

	public CouponNotFoundException() {
		super(ErrorCode.COUPON_NOT_FOUND);
	}
}
