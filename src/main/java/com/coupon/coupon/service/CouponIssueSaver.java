package com.coupon.coupon.service;

import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.repository.CouponIssueRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.stereotype.Service;

/**
 * 발급 기록을 DB에 저장한다. 같은 발급이 여러 번 들어와도 한 건만 남는다 (멱등).
 * (쿠폰 id, 유저 id) 유니크 제약에 걸리면 이미 저장된 것으로 보고 성공으로 끝낸다.
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CouponIssueSaver {

	private final CouponIssueRepository couponIssueRepository;

	/** 새로 저장했으면 true, 이미 있었으면 false. 그 밖의 DB 오류는 그대로 던져 재시도하게 한다. */
	public boolean save(CouponIssueMessage message) {
		try {
			couponIssueRepository.save(new CouponIssue(message.couponId(), message.userId(), message.issuedAt()));
			return true;
		} catch (DataIntegrityViolationException e) {
			if (couponIssueRepository.existsByCouponIdAndUserId(message.couponId(), message.userId())) {
				log.info("이미 저장된 발급이라 건너뜀: couponId={}, userId={}", message.couponId(), message.userId());
				return false;
			}
			throw e;
		}
	}
}
