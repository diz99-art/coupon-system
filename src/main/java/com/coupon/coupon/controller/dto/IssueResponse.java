package com.coupon.coupon.controller.dto;

import java.time.LocalDateTime;

public record IssueResponse(Long couponId, Long userId, LocalDateTime issuedAt) {
}
