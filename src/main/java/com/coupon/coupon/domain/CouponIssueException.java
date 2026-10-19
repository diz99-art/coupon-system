package com.coupon.coupon.domain;

import lombok.Getter;

/** 쿠폰을 발급할 수 없을 때 던지는 예외. 이유는 ErrorCode로 구분한다. */
@Getter
public class CouponIssueException extends RuntimeException {

	public enum ErrorCode {
		COUPON_NOT_FOUND,
		NOT_IN_ISSUE_PERIOD,
		SOLD_OUT,
		ALREADY_ISSUED
	}

	private final ErrorCode errorCode;

	public CouponIssueException(ErrorCode errorCode) {
		super(errorCode.name());
		this.errorCode = errorCode;
	}
}
