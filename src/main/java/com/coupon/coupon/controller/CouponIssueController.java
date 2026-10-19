package com.coupon.coupon.controller;

import com.coupon.coupon.controller.dto.IssueRequest;
import com.coupon.coupon.controller.dto.IssueResponse;
import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.service.KafkaCouponIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "발급", description = "선착순 쿠폰 발급")
@RestController
@RequiredArgsConstructor
public class CouponIssueController {

	private final KafkaCouponIssueService couponIssueService;

	@Operation(summary = "쿠폰 발급", description = """
			발급이 확정되면 202로 바로 응답하고, DB 저장은 Kafka 컨슈머가 이어서 한다.
			실패: 404 COUPON_NOT_FOUND, 409 COUPON_NOT_IN_ISSUE_PERIOD / COUPON_SOLD_OUT / COUPON_ALREADY_ISSUED,
			503 COUPON_ISSUE_BUSY, 400 INVALID_REQUEST""")
	@PostMapping("/coupons/{couponId}/issue")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public IssueResponse issue(@PathVariable Long couponId, @Valid @RequestBody IssueRequest request) {
		CouponIssueMessage issue = couponIssueService.issue(couponId, request.userId());
		return new IssueResponse(issue.couponId(), issue.userId(), issue.issuedAt());
	}
}
