package com.coupon.coupon.service;

import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.domain.exception.CouponIssueBusyException;
import java.util.concurrent.TimeUnit;
import lombok.RequiredArgsConstructor;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.stereotype.Service;

/**
 * Redisson 분산 락으로 발급. 쿠폰 id마다 Redis에 락을 하나 두고, 락을 잡은 요청만 DB 발급 로직을 실행한다.
 * 락은 Redis에 있으므로 서버가 여러 대여도 같은 쿠폰은 한 번에 한 요청만 처리된다.
 * 이 클래스에는 @Transactional을 붙이지 않는다. 안쪽 트랜잭션이 커밋된 뒤에 락을 풀어야 하기 때문이다.
 */
@Service
@RequiredArgsConstructor
public class RedissonLockCouponIssueService implements CouponIssuer {

	private static final String LOCK_KEY_PREFIX = "coupon:lock:";
	/** 락을 얻을 때까지 기다리는 최대 시간 */
	private static final long WAIT_TIME_SECONDS = 10;
	/** 락을 잡은 서버가 죽어도 이 시간이 지나면 락이 저절로 풀린다. 발급 한 건보다 충분히 길어야 한다. */
	private static final long LEASE_TIME_SECONDS = 3;

	private final RedissonClient redissonClient;
	/** 정확도가 락에서만 나오도록 @Version 확인이 없는 발급 로직을 감싼다. */
	private final NoLockCouponIssueService noLockCouponIssueService;

	@Override
	public CouponIssue issue(Long couponId, Long userId) {
		RLock lock = redissonClient.getLock(LOCK_KEY_PREFIX + couponId);
		boolean locked = false;
		try {
			locked = lock.tryLock(WAIT_TIME_SECONDS, LEASE_TIME_SECONDS, TimeUnit.SECONDS);
			if (!locked) {
				throw new CouponIssueBusyException();
			}
			// 이 호출이 돌아오면 트랜잭션은 이미 커밋됐다.
			return noLockCouponIssueService.issue(couponId, userId);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new CouponIssueBusyException();
		} finally {
			// leaseTime이 지나 이미 풀렸으면 남의 락을 풀지 않도록 확인한다.
			if (locked && lock.isHeldByCurrentThread()) {
				lock.unlock();
			}
		}
	}
}
