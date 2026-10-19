package com.coupon.coupon.domain.exception;

/** 쿠폰 수정에서 총 수량을 이미 발급된 수량보다 적게 바꾸려 했다. */
public class CouponQuantityBelowIssuedException extends CouponException {

	public CouponQuantityBelowIssuedException() {
		super(ErrorCode.COUPON_QUANTITY_BELOW_ISSUED);
	}
}
