package com.coupon.coupon.messaging;

import java.time.LocalDateTime;

/** Redis 판정을 통과한 발급 한 건. 컨슈머가 받아서 DB에 CouponIssue로 저장한다. */
public record CouponIssueMessage(Long couponId, Long userId, LocalDateTime issuedAt) {
}
