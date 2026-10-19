package com.coupon.coupon.repository;

import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis에 두는 쿠폰 발급 상태. 쿠폰마다 받은 유저 SET과 발급 수량 카운터를 둔다.
 * 여러 명령을 묶어야 하는 동작은 Lua 스크립트로 Redis 안에서 원자적으로 실행한다.
 */
@Repository
public class CouponIssueRedisRepository {

	public enum IssueResult {
		OK, ALREADY_ISSUED, SOLD_OUT
	}

	private static final String USERS_KEY = "coupon:%d:users";
	private static final String COUNT_KEY = "coupon:%d:count";

	/** KEYS[1] 받은 유저 SET, KEYS[2] 발급 수량, ARGV[1] 유저 id, ARGV[2] 총 수량. 1 발급, 2 이미 받음, 3 매진 */
	private static final RedisScript<Long> TRY_ISSUE = RedisScript.of("""
			if redis.call('SISMEMBER', KEYS[1], ARGV[1]) == 1 then
				return 2
			end
			local count = tonumber(redis.call('GET', KEYS[2]) or '0')
			if count >= tonumber(ARGV[2]) then
				return 3
			end
			redis.call('INCR', KEYS[2])
			redis.call('SADD', KEYS[1], ARGV[1])
			return 1
			""", Long.class);

	/** 발급을 되돌린다. 유저가 SET에 있을 때만 수량을 줄여서 두 번 불려도 한 번만 줄어든다. */
	private static final RedisScript<Long> CANCEL = RedisScript.of("""
			if redis.call('SREM', KEYS[1], ARGV[1]) == 1 then
				redis.call('DECR', KEYS[2])
				return 1
			end
			return 0
			""", Long.class);

	/** DB에만 있는 유저를 SET에 다시 넣고, 수량이 SET 크기보다 작으면 맞춘다. */
	private static final RedisScript<Long> RESTORE = RedisScript.of("""
			for i = 1, #ARGV do
				redis.call('SADD', KEYS[1], ARGV[i])
			end
			local size = redis.call('SCARD', KEYS[1])
			local count = tonumber(redis.call('GET', KEYS[2]) or '0')
			if count < size then
				redis.call('SET', KEYS[2], size)
			end
			return size
			""", Long.class);

	private final StringRedisTemplate redisTemplate;

	public CouponIssueRedisRepository(StringRedisTemplate redisTemplate) {
		this.redisTemplate = redisTemplate;
	}

	public IssueResult tryIssue(Long couponId, Long userId, int totalQuantity) {
		Long result = redisTemplate.execute(TRY_ISSUE, keys(couponId),
				String.valueOf(userId), String.valueOf(totalQuantity));
		if (result != null && result == 1) {
			return IssueResult.OK;
		}
		if (result != null && result == 2) {
			return IssueResult.ALREADY_ISSUED;
		}
		return IssueResult.SOLD_OUT;
	}

	public void cancel(Long couponId, Long userId) {
		redisTemplate.execute(CANCEL, keys(couponId), String.valueOf(userId));
	}

	public void restore(Long couponId, Collection<Long> userIds) {
		if (userIds.isEmpty()) {
			return;
		}
		String[] args = userIds.stream().map(String::valueOf).toArray(String[]::new);
		redisTemplate.execute(RESTORE, keys(couponId), (Object[]) args);
	}

	public Set<Long> issuedUserIds(Long couponId) {
		Set<String> members = redisTemplate.opsForSet().members(USERS_KEY.formatted(couponId));
		if (members == null) {
			return Set.of();
		}
		return members.stream().map(Long::valueOf).collect(Collectors.toSet());
	}

	public long issuedCount(Long couponId) {
		String value = redisTemplate.opsForValue().get(COUNT_KEY.formatted(couponId));
		return value == null ? 0 : Long.parseLong(value);
	}

	private List<String> keys(Long couponId) {
		return List.of(USERS_KEY.formatted(couponId), COUNT_KEY.formatted(couponId));
	}
}
