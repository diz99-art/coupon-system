package com.coupon.coupon.controller;

import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.service.KafkaCouponIssueService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CouponIssueController {

	private final KafkaCouponIssueService couponIssueService;

	/** 발급이 확정되면 202로 바로 응답한다. DB 저장은 Kafka 컨슈머가 이어서 한다. */
	@PostMapping("/coupons/{couponId}/issue")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public IssueResponse issue(@PathVariable Long couponId, @RequestBody IssueRequest request) {
		CouponIssueMessage issue = couponIssueService.issue(couponId, request.userId());
		return new IssueResponse(issue.couponId(), issue.userId(), issue.issuedAt());
	}

	public record IssueRequest(Long userId) {
	}

	public record IssueResponse(Long couponId, Long userId, LocalDateTime issuedAt) {
	}
}
