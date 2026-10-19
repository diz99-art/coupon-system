package com.coupon.coupon.controller;

import com.coupon.coupon.domain.CouponIssueException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** 발급 실패 이유를 HTTP 상태 코드와 에러 코드로 바꿔 응답한다. */
@RestControllerAdvice
public class CouponExceptionHandler {

	@ExceptionHandler(CouponIssueException.class)
	public ResponseEntity<ErrorResponse> handle(CouponIssueException e) {
		HttpStatus status = switch (e.getErrorCode()) {
			case COUPON_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case NOT_IN_ISSUE_PERIOD -> HttpStatus.BAD_REQUEST;
			case SOLD_OUT, ALREADY_ISSUED -> HttpStatus.CONFLICT;
		};
		return ResponseEntity.status(status).body(new ErrorResponse(e.getErrorCode().name()));
	}

	public record ErrorResponse(String code) {
	}
}
