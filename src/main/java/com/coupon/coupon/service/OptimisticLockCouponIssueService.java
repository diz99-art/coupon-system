package com.coupon.coupon.service;

import com.coupon.coupon.domain.CouponIssue;
import java.util.concurrent.atomic.AtomicLong;
import lombok.RequiredArgsConstructor;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.stereotype.Service;

/**
 * 낙관적 락으로 발급. 버전 충돌이 나면 새 트랜잭션으로 처음부터 다시 시도한다.
 * 재시도는 트랜잭션 바깥에서 해야 한다. 실패한 트랜잭션은 이미 되돌려졌고, 새로 읽어야 최신 버전을 보기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class OptimisticLockCouponIssueService implements CouponIssuer {

	private static final long RETRY_WAIT_MILLIS = 10;

	private final OptimisticLockCouponIssueTransaction transaction;
	/** 지금까지 일어난 재시도 횟수 (비교 측정용) */
	private final AtomicLong retryCount = new AtomicLong();

	@Override
	public CouponIssue issue(Long couponId, Long userId) {
		while (true) {
			try {
				return transaction.issue(couponId, userId);
			} catch (OptimisticLockingFailureException e) {
				retryCount.incrementAndGet();
				waitBeforeRetry();
			}
		}
	}

	public long getRetryCount() {
		return retryCount.get();
	}

	public void resetRetryCount() {
		retryCount.set(0);
	}

	private void waitBeforeRetry() {
		try {
			Thread.sleep(RETRY_WAIT_MILLIS);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException("재시도 대기 중 중단됨", e);
		}
	}
}
