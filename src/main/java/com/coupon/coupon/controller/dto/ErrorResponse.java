package com.coupon.coupon.controller.dto;

import java.util.List;

/** 모든 에러 응답의 형식. errors는 요청 값 검증에 실패한 필드 목록이고, 그 밖의 에러에서는 빈 배열이다. */
public record ErrorResponse(String code, String message, List<FieldError> errors) {

	public ErrorResponse(String code, String message) {
		this(code, message, List.of());
	}

	public record FieldError(String field, String message) {
	}
}
