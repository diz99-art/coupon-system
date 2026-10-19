package com.coupon.coupon.controller;

import com.coupon.coupon.controller.dto.CouponCreateRequest;
import com.coupon.coupon.controller.dto.CouponResponse;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.service.CouponService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.net.URI;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

@Tag(name = "쿠폰", description = "쿠폰 생성과 조회")
@RestController
@RequiredArgsConstructor
public class CouponController {

	private final CouponService couponService;

	@Operation(summary = "쿠폰 생성", description = "201과 함께 Location 헤더에 새 쿠폰 주소를 준다. 검증 실패는 400 INVALID_REQUEST.")
	@PostMapping("/coupons")
	public ResponseEntity<CouponResponse> create(@Valid @RequestBody CouponCreateRequest request) {
		Coupon coupon = couponService.create(request.name(), request.totalQuantity(),
				request.issueStartAt(), request.issueEndAt());
		return ResponseEntity.created(URI.create("/coupons/" + coupon.getId()))
				.body(CouponResponse.of(coupon, 0));
	}

	@Operation(summary = "쿠폰 조회", description = "남은 수량은 Redis 카운터 기준. 쿠폰이 없으면 404 COUPON_NOT_FOUND.")
	@GetMapping("/coupons/{couponId}")
	public CouponResponse get(@PathVariable Long couponId) {
		Coupon coupon = couponService.get(couponId);
		return CouponResponse.of(coupon, couponService.issuedQuantity(couponId));
	}
}
