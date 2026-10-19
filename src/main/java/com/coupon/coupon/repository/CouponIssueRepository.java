package com.coupon.coupon.repository;

import com.coupon.coupon.domain.CouponIssue;
import java.util.List;
import java.util.Set;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface CouponIssueRepository extends JpaRepository<CouponIssue, Long> {

	boolean existsByCouponIdAndUserId(Long couponId, Long userId);

	long countByCouponId(Long couponId);

	List<CouponIssue> findByCouponId(Long couponId);

	List<CouponIssue> findByUserIdOrderByIssuedAtDesc(Long userId);

	@Query("select ci.userId from CouponIssue ci where ci.couponId = :couponId")
	Set<Long> findUserIdsByCouponId(Long couponId);
}
