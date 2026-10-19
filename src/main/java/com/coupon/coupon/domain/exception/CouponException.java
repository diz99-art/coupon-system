package com.coupon.coupon.domain.exception;

import lombok.Getter;

/** 쿠폰 업무 규칙을 어겼을 때 던지는 예외들의 부모. GlobalExceptionHandler가 한 곳에서 응답으로 바꾼다. */
@Getter
public abstract class CouponException extends RuntimeException {

	private final ErrorCode errorCode;

	protected CouponException(ErrorCode errorCode) {
		super(errorCode.getMessage());
		this.errorCode = errorCode;
	}
}
