package com.coupon.coupon.domain.exception;

/** 발급 시작 전이거나 종료 후다. */
public class CouponNotInIssuePeriodException extends CouponException {

	public CouponNotInIssuePeriodException() {
		super(ErrorCode.COUPON_NOT_IN_ISSUE_PERIOD);
	}
}
