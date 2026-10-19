package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** 쿠폰 생성과 조회. 발급은 KafkaCouponIssueService가 맡는다. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponIssueRedisRepository couponIssueRedisRepository;

	@Transactional
	public Coupon create(String name, int totalQuantity, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
		return couponRepository.save(new Coupon(name, totalQuantity, issueStartAt, issueEndAt));
	}

	public Coupon get(Long couponId) {
		return couponRepository.findById(couponId).orElseThrow(CouponNotFoundException::new);
	}

	/** 발급 수량의 기준은 Redis 카운터다. DB의 issuedQuantity는 Kafka 방식에서 갱신하지 않는다. */
	public long issuedQuantity(Long couponId) {
		return couponIssueRedisRepository.issuedCount(couponId);
	}

	/**
	 * 유저가 받은 쿠폰을 최근 발급 순으로 돌려준다. DB 기준이라 발급 직후 컨슈머가 저장하기 전에는 보이지 않을 수 있다.
	 * 쿠폰 이름은 쿠폰마다 따로 조회하지 않고 한 번에 가져온다 (N+1 조회 방지).
	 */
	public List<UserCoupon> findUserCoupons(Long userId) {
		List<CouponIssue> issues = couponIssueRepository.findByUserIdOrderByIssuedAtDesc(userId);
		List<Long> couponIds = issues.stream().map(CouponIssue::getCouponId).distinct().toList();
		Map<Long, Coupon> coupons = couponRepository.findAllById(couponIds).stream()
				.collect(Collectors.toMap(Coupon::getId, Function.identity()));
		return issues.stream()
				.filter(issue -> coupons.containsKey(issue.getCouponId()))
				.map(issue -> new UserCoupon(coupons.get(issue.getCouponId()), issue))
				.toList();
	}

	public record UserCoupon(Coupon coupon, CouponIssue issue) {
	}
}
