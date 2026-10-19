package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.domain.exception.CouponQuantityBelowIssuedException;
import com.coupon.coupon.repository.CouponInfo;
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
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * 쿠폰 생성, 수정, 조회. 발급은 KafkaCouponIssueService가 맡는다.
 * 생성·수정할 때 Redis의 쿠폰 정보(발급 판정에 쓰는 사본)도 함께 바꾼다 (write-through).
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CouponService {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponIssueRedisRepository couponIssueRedisRepository;

	@Transactional
	public Coupon create(String name, int totalQuantity, LocalDateTime issueStartAt, LocalDateTime issueEndAt) {
		Coupon coupon = couponRepository.save(new Coupon(name, totalQuantity, issueStartAt, issueEndAt));
		// 커밋된 뒤에 올린다. 커밋 전에 올리면, 롤백된(존재하지 않는) 쿠폰을 발급할 수 있다.
		// 올리다 실패해도 첫 발급 요청이 DB에서 읽어 올린다.
		CouponInfo info = CouponInfo.from(coupon);
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCommit() {
				couponIssueRedisRepository.loadInfo(info);
			}
		});
		return coupon;
	}

	/**
	 * 쿠폰 수정. 총 수량을 이미 발급된 수량보다 적게 바꿀 수는 없다 (409).
	 * 순서: DB 엔티티 변경 → Redis 쿠폰 정보 변경(수량 확인과 함께 원자적으로) → DB 커밋.
	 * Redis를 커밋 전에 바꾸는 이유: 수량 확인이 Redis에서 끝나야 하고, 확인에 실패하면 DB도 롤백해야 하기 때문이다.
	 * 그 뒤 DB 커밋이 실패하면 Redis만 새 값이 되므로, 쿠폰 정보를 지워 다음 발급 요청이 DB의 값을 다시 읽게 한다.
	 */
	@Transactional
	public Coupon update(Long couponId, String name, int totalQuantity, LocalDateTime issueStartAt,
			LocalDateTime issueEndAt) {
		Coupon coupon = get(couponId);
		coupon.update(name, totalQuantity, issueStartAt, issueEndAt);
		if (!couponIssueRedisRepository.updateInfo(CouponInfo.from(coupon))) {
			// Redis는 바뀌지 않았다. 예외로 DB 변경만 롤백한다.
			throw new CouponQuantityBelowIssuedException();
		}
		// Redis는 이미 새 값이다. 이 트랜잭션이 커밋되지 못하면 Redis 사본을 지워 DB와 어긋난 채로 두지 않는다.
		TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
			@Override
			public void afterCompletion(int status) {
				if (status != STATUS_COMMITTED) {
					couponIssueRedisRepository.deleteInfo(couponId);
				}
			}
		});
		return coupon;
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
