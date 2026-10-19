package com.coupon.coupon.domain.exception;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/** 에러 응답의 code와 기본 message. HTTP 상태 코드는 컨트롤러 계층(GlobalExceptionHandler)에서 정한다. */
@Getter
@RequiredArgsConstructor
public enum ErrorCode {

	COUPON_NOT_FOUND("쿠폰을 찾을 수 없습니다."),
	COUPON_NOT_IN_ISSUE_PERIOD("쿠폰 발급 기간이 아닙니다."),
	COUPON_SOLD_OUT("쿠폰이 모두 소진되었습니다."),
	COUPON_ALREADY_ISSUED("이미 발급받은 쿠폰입니다."),
	COUPON_ISSUE_BUSY("요청이 많아 처리하지 못했습니다. 잠시 뒤 다시 시도해 주세요."),
	INVALID_REQUEST("요청 값이 올바르지 않습니다."),
	INTERNAL_ERROR("서버 오류가 발생했습니다.");

	private final String message;
}
