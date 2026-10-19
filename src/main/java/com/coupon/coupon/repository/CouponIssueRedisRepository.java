package com.coupon.coupon.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis에 두는 쿠폰 발급 상태. 쿠폰마다 받은 유저 Sorted Set과 발급 수량 카운터를 둔다.
 * Sorted Set의 member는 유저 id, score는 발급 시각(epoch 밀리초)이다. 대사 작업이 원래 발급 시각으로 DB를 복구할 수 있다.
 * 여러 명령을 묶어야 하는 동작은 Lua 스크립트로 Redis 안에서 원자적으로 실행한다.
 */
@Repository
public class CouponIssueRedisRepository {

	public enum IssueResult {
		OK, ALREADY_ISSUED, SOLD_OUT
	}

	/** 쿠폰 관련 키는 모두 이 접두어로 시작한다 (개발 서버 시작 시 초기화, 테스트 정리에 쓴다). */
	public static final String KEY_PATTERN = "coupon:*";

	private static final String USERS_KEY = "coupon:%d:users";
	private static final String COUNT_KEY = "coupon:%d:count";
	private static final ZoneId ZONE = ZoneId.systemDefault();

	/**
	 * KEYS[1] 받은 유저 Sorted Set, KEYS[2] 발급 수량, ARGV[1] 유저 id, ARGV[2] 총 수량, ARGV[3] 발급 시각(epoch 밀리초).
	 * 1 발급, 2 이미 받음, 3 매진
	 */
	private static final RedisScript<Long> TRY_ISSUE = RedisScript.of("""
			if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
				return 2
			end
			local count = tonumber(redis.call('GET', KEYS[2]) or '0')
			if count >= tonumber(ARGV[2]) then
				return 3
			end
			redis.call('INCR', KEYS[2])
			redis.call('ZADD', KEYS[1], ARGV[3], ARGV[1])
			return 1
			""", Long.class);

	/** 발급을 되돌린다. 유저가 있을 때만 수량을 줄여서 두 번 불려도 한 번만 줄어든다. */
	private static final RedisScript<Long> CANCEL = RedisScript.of("""
			if redis.call('ZREM', KEYS[1], ARGV[1]) == 1 then
				redis.call('DECR', KEYS[2])
				return 1
			end
			return 0
			""", Long.class);

	/** DB에만 있는 유저를 발급 시각과 함께 다시 넣고, 수량이 유저 수보다 작으면 맞춘다. ARGV는 (시각, 유저 id) 쌍. */
	private static final RedisScript<Long> RESTORE = RedisScript.of("""
			for i = 1, #ARGV, 2 do
				redis.call('ZADD', KEYS[1], ARGV[i], ARGV[i + 1])
			end
			local size = redis.call('ZCARD', KEYS[1])
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

	public IssueResult tryIssue(Long couponId, Long userId, int totalQuantity, LocalDateTime issuedAt) {
		Long result = redisTemplate.execute(TRY_ISSUE, keys(couponId),
				String.valueOf(userId), String.valueOf(totalQuantity), String.valueOf(toEpochMillis(issuedAt)));
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

	/** 유저 id → 발급 시각 */
	public void restore(Long couponId, Map<Long, LocalDateTime> issues) {
		if (issues.isEmpty()) {
			return;
		}
		List<String> args = new ArrayList<>();
		issues.forEach((userId, issuedAt) -> {
			args.add(String.valueOf(toEpochMillis(issuedAt)));
			args.add(String.valueOf(userId));
		});
		redisTemplate.execute(RESTORE, keys(couponId), args.toArray());
	}

	/** 유저 id → 발급 시각 */
	public Map<Long, LocalDateTime> issuedUsers(Long couponId) {
		Set<TypedTuple<String>> tuples = redisTemplate.opsForZSet().rangeWithScores(USERS_KEY.formatted(couponId), 0, -1);
		Map<Long, LocalDateTime> issues = new HashMap<>();
		if (tuples != null) {
			for (TypedTuple<String> tuple : tuples) {
				issues.put(Long.valueOf(tuple.getValue()), fromEpochMillis(tuple.getScore().longValue()));
			}
		}
		return issues;
	}

	public Set<Long> issuedUserIds(Long couponId) {
		return issuedUsers(couponId).keySet();
	}

	public long issuedCount(Long couponId) {
		String value = redisTemplate.opsForValue().get(COUNT_KEY.formatted(couponId));
		return value == null ? 0 : Long.parseLong(value);
	}

	/** 쿠폰 관련 키를 모두 지운다. KEYS 대신 SCAN으로 조금씩 찾아서 Redis를 오래 막지 않는다. */
	public long deleteAll() {
		List<String> found = new ArrayList<>();
		try (Cursor<String> cursor = redisTemplate.scan(ScanOptions.scanOptions().match(KEY_PATTERN).count(500).build())) {
			cursor.forEachRemaining(found::add);
		}
		if (found.isEmpty()) {
			return 0;
		}
		Long deleted = redisTemplate.delete(found);
		return deleted == null ? 0 : deleted;
	}

	private List<String> keys(Long couponId) {
		return List.of(USERS_KEY.formatted(couponId), COUNT_KEY.formatted(couponId));
	}

	private static long toEpochMillis(LocalDateTime time) {
		return time.atZone(ZONE).toInstant().toEpochMilli();
	}

	private static LocalDateTime fromEpochMillis(long millis) {
		return LocalDateTime.ofInstant(Instant.ofEpochMilli(millis), ZONE);
	}
}
