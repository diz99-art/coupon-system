package com.coupon.coupon.domain.exception;

/** 락 대기 시간이 지났거나 발급 메시지를 보내지 못했다. 잠시 뒤 다시 시도하면 된다. */
public class CouponIssueBusyException extends CouponException {

	public CouponIssueBusyException() {
		super(ErrorCode.COUPON_ISSUE_BUSY);
	}
}
