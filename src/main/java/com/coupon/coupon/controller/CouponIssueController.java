package com.coupon.coupon.controller;

import com.coupon.coupon.controller.dto.IssueResponse;
import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.service.KafkaCouponIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.security.oauth2.jwt.Jwt;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "발급", description = "선착순 쿠폰 발급")
@RestController
@RequiredArgsConstructor
public class CouponIssueController {

	private final KafkaCouponIssueService couponIssueService;

	@Operation(summary = "쿠폰 발급", description = """
			발급이 확정되면 202로 바로 응답하고, DB 저장은 Kafka 컨슈머가 이어서 한다. (k3s 클러스터에는 ArgoCD가 GitOps로 배포)
			실패: 404 COUPON_NOT_FOUND, 409 COUPON_NOT_IN_ISSUE_PERIOD / COUPON_SOLD_OUT / COUPON_ALREADY_ISSUED,
			503 COUPON_ISSUE_BUSY, 401 UNAUTHORIZED(토큰 없음·만료·위조). 발급받는 사람은 토큰의 사용자다 (요청 본문 없음)""", security = @SecurityRequirement(name = "bearer"))
	@PostMapping("/coupons/{couponId}/issue")
	@ResponseStatus(HttpStatus.ACCEPTED)
	public IssueResponse issue(@PathVariable Long couponId, @AuthenticationPrincipal Jwt jwt) {
		// 발급받는 사람은 요청 본문이 아니라 토큰의 sub(인증 서버가 서명한 사용자 id)로 정한다.
		// 본문의 userId를 믿으면 로그인한 누구나 다른 사람 id로 쿠폰을 가로챌 수 있다 (README 10단계)
		Long userId = Long.valueOf(jwt.getSubject());
		CouponIssueMessage issue = couponIssueService.issue(couponId, userId);
		return new IssueResponse(issue.couponId(), issue.userId(), issue.issuedAt());
	}
}
