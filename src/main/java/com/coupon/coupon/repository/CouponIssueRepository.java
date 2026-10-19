package com.coupon.coupon.repository;

import com.coupon.coupon.domain.CouponIssue;
import org.springframework.data.jpa.repository.JpaRepository;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	boolean existsByCouponIdAndUserId(Long couponId, Long userId);

	long countByCouponId(Long couponId);
}
