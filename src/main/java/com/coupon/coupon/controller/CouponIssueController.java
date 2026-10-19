package com.coupon.coupon.controller;

import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.service.PessimisticLockCouponIssueService;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequiredArgsConstructor
public class CouponIssueController {

	private final PessimisticLockCouponIssueService couponIssueService;

	@PostMapping("/coupons/{couponId}/issue")
	public IssueResponse issue(@PathVariable Long couponId, @RequestBody IssueRequest request) {
		CouponIssue issue = couponIssueService.issue(couponId, request.userId());
		return new IssueResponse(issue.getId(), issue.getCouponId(), issue.getUserId(), issue.getIssuedAt());
	}

	public record IssueRequest(Long userId) {
	}

	public record IssueResponse(Long issueId, Long couponId, Long userId, LocalDateTime issuedAt) {
	}
}
