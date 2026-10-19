package com.coupon.coupon.controller;

import com.coupon.coupon.domain.CouponIssueException;
import org.springframework.dao.DataIntegrityViolationException;
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
			case BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
		};
		return ResponseEntity.status(status).body(new ErrorResponse(e.getErrorCode().name()));
	}

	/** 같은 유저가 동시에 요청해 조회 확인을 통과해도, (쿠폰 id, 유저 id) 유니크 제약에 걸리면 중복 발급으로 응답한다. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDuplicate(DataIntegrityViolationException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse("ALREADY_ISSUED"));
	}

	public record ErrorResponse(String code) {
	}
}
