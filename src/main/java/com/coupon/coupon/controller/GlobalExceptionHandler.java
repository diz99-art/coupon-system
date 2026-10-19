package com.coupon.coupon.controller;

import com.coupon.coupon.controller.dto.ErrorResponse;
import com.coupon.coupon.domain.exception.CouponException;
import com.coupon.coupon.domain.exception.ErrorCode;
import java.util.List;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;

/**
 * 모든 예외를 한 곳에서 {code, message, errors} 형식으로 바꾼다.
 * 상태 코드를 정하는 것은 HTTP의 일이라 도메인 예외가 아니라 여기서 정한다.
 */
@Slf4j
@RestControllerAdvice
public class GlobalExceptionHandler {

	/** 서버가 붐벼서 실패했을 때 몇 초 뒤 다시 시도하라고 알려준다. */
	private static final String RETRY_AFTER_SECONDS = "1";

	@ExceptionHandler(CouponException.class)
	public ResponseEntity<ErrorResponse> handleCoupon(CouponException e) {
		ErrorCode code = e.getErrorCode();
		HttpStatus status = switch (code) {
			case COUPON_NOT_FOUND -> HttpStatus.NOT_FOUND;
			case COUPON_NOT_IN_ISSUE_PERIOD, COUPON_SOLD_OUT, COUPON_ALREADY_ISSUED -> HttpStatus.CONFLICT;
			case COUPON_ISSUE_BUSY -> HttpStatus.SERVICE_UNAVAILABLE;
			case INVALID_REQUEST -> HttpStatus.BAD_REQUEST;
			case INTERNAL_ERROR -> HttpStatus.INTERNAL_SERVER_ERROR;
		};
		ResponseEntity.BodyBuilder builder = ResponseEntity.status(status);
		if (status == HttpStatus.SERVICE_UNAVAILABLE) {
			builder.header(HttpHeaders.RETRY_AFTER, RETRY_AFTER_SECONDS);
		}
		return builder.body(new ErrorResponse(code.name(), e.getMessage()));
	}

	/** @Valid 검증 실패. 어떤 필드가 왜 틀렸는지 모두 알려준다. */
	@ExceptionHandler(MethodArgumentNotValidException.class)
	public ResponseEntity<ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
		List<ErrorResponse.FieldError> errors = e.getBindingResult().getFieldErrors().stream()
				.map(error -> new ErrorResponse.FieldError(error.getField(), error.getDefaultMessage()))
				.toList();
		return badRequest(ErrorCode.INVALID_REQUEST.getMessage(), errors);
	}

	/** JSON 형식이 깨졌거나 날짜·숫자 형식이 틀려 요청 본문을 읽지 못했다. */
	@ExceptionHandler(HttpMessageNotReadableException.class)
	public ResponseEntity<ErrorResponse> handleNotReadable(HttpMessageNotReadableException e) {
		return badRequest("요청 본문을 읽을 수 없습니다. JSON 형식과 값의 형식(날짜는 yyyy-MM-ddTHH:mm:ss)을 확인해 주세요.", List.of());
	}

	/** 주소의 id 자리에 숫자가 아닌 값이 왔다. */
	@ExceptionHandler(MethodArgumentTypeMismatchException.class)
	public ResponseEntity<ErrorResponse> handleTypeMismatch(MethodArgumentTypeMismatchException e) {
		return badRequest("'%s' 값의 형식이 올바르지 않습니다.".formatted(e.getName()), List.of());
	}

	/** 유니크 제약 위반. 동기 저장 방식(비교용 구현)에서 같은 유저가 동시에 요청했을 때 생긴다. */
	@ExceptionHandler(DataIntegrityViolationException.class)
	public ResponseEntity<ErrorResponse> handleDuplicate(DataIntegrityViolationException e) {
		return ResponseEntity.status(HttpStatus.CONFLICT).body(new ErrorResponse(
				ErrorCode.COUPON_ALREADY_ISSUED.name(), ErrorCode.COUPON_ALREADY_ISSUED.getMessage()));
	}

	/**
	 * 그 밖의 예외. 스프링이 상태 코드를 정해 둔 예외(없는 주소 404, 허용되지 않은 메서드 405 등)는 그 코드를 따르고,
	 * 나머지는 500으로 응답한다. 500의 자세한 내용은 로그에만 남기고 응답에는 넣지 않는다.
	 */
	@ExceptionHandler(Exception.class)
	public ResponseEntity<ErrorResponse> handleOthers(Exception e) {
		if (e instanceof org.springframework.web.ErrorResponse springError) {
			HttpStatusCode status = springError.getStatusCode();
			String detail = springError.getBody().getDetail();
			return ResponseEntity.status(status).body(new ErrorResponse(
					ErrorCode.INVALID_REQUEST.name(), detail != null ? detail : ErrorCode.INVALID_REQUEST.getMessage()));
		}
		log.error("처리하지 못한 예외", e);
		return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).body(new ErrorResponse(
				ErrorCode.INTERNAL_ERROR.name(), ErrorCode.INTERNAL_ERROR.getMessage()));
	}

	private ResponseEntity<ErrorResponse> badRequest(String message, List<ErrorResponse.FieldError> errors) {
		return ResponseEntity.badRequest().body(new ErrorResponse(ErrorCode.INVALID_REQUEST.name(), message, errors));
	}
}
