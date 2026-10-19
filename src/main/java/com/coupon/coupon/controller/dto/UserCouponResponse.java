package com.coupon.coupon.controller.dto;

import java.time.LocalDateTime;

public record UserCouponResponse(Long couponId, String couponName, LocalDateTime issuedAt) {
}
