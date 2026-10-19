package com.coupon.coupon.service;

import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.CouponIssueException;
import com.coupon.coupon.domain.CouponIssueException.ErrorCode;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.LocalDateTime;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * synchronized로 한 번에 한 스레드만 들어오게 한 발급.
 * 하지만 @Transactional 프록시가 synchronized 바깥에서 커밋하기 때문에, 커밋 전에 다음 스레드가 들어와 옛 값을 읽을 수 있다.
 * 서버가 여러 대면 서버마다 따로 잠기므로 소용없다.
 * Coupon의 @Version이 대신 막아주지 않도록 수량은 1단계와 같이 버전 확인 없는 UPDATE로 덮어쓴다.
 */
@Service
@RequiredArgsConstructor
public class SynchronizedCouponIssueService implements CouponIssuer {

	private final CouponRepository couponRepository;
	private final CouponIssueRepository couponIssueRepository;

	@Override
	@Transactional
	public synchronized CouponIssue issue(Long couponId, Long userId) {
		LocalDateTime now = LocalDateTime.now();

		Coupon coupon = couponRepository.findById(couponId)
				.orElseThrow(() -> new CouponIssueException(ErrorCode.COUPON_NOT_FOUND));
		coupon.validateIssuable(now);
		if (couponIssueRepository.existsByCouponIdAndUserId(couponId, userId)) {
			throw new CouponIssueException(ErrorCode.ALREADY_ISSUED);
		}

		couponRepository.overwriteIssuedQuantity(couponId, coupon.getIssuedQuantity() + 1);
		return couponIssueRepository.save(new CouponIssue(couponId, userId, now));
	}
}
