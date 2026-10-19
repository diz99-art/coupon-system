package com.coupon.coupon.domain.exception;

/** 같은 유저가 이미 받았다. */
public class CouponAlreadyIssuedException extends CouponException {

	public CouponAlreadyIssuedException() {
		super(ErrorCode.COUPON_ALREADY_ISSUED);
	}
}
