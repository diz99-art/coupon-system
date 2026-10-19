package com.coupon.coupon.controller;

import com.coupon.coupon.controller.dto.UserCouponResponse;
import com.coupon.coupon.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "내 쿠폰", description = "유저별 발급 내역")
@RestController
@RequiredArgsConstructor
public class UserCouponController {

	private final CouponService couponService;

	@Operation(summary = "내 발급 내역 조회",
			description = "최근 발급 순. DB 기준이라 발급 직후 잠깐은 목록에 없을 수 있다 (Kafka 컨슈머가 저장한 뒤 보인다).")
	@GetMapping("/users/{userId}/coupons")
	public List<UserCouponResponse> findUserCoupons(@PathVariable Long userId) {
		return couponService.findUserCoupons(userId).stream()
				.map(c -> new UserCouponResponse(c.coupon().getId(), c.coupon().getName(), c.issue().getIssuedAt()))
				.toList();
	}
}
