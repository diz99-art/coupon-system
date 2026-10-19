package com.coupon.coupon.repository;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ZSetOperations.TypedTuple;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.stereotype.Repository;

/**
 * Redis에 두는 쿠폰 발급 상태. 쿠폰마다 받은 유저 Sorted Set, 발급 수량 카운터, 쿠폰 정보 해시를 둔다.
 * Sorted Set의 member는 유저 id, score는 발급 시각(epoch 밀리초)이다. 대사 작업이 원래 발급 시각으로 DB를 복구할 수 있다.
 * 여러 명령을 묶어야 하는 동작은 Lua 스크립트로 Redis 안에서 원자적으로 실행한다.
 * 쿠폰 정보 해시(total, start, end)는 DB Coupon의 사본이다. 발급 판정을 Redis 안에서 끝내서 요청마다 DB를 읽지 않으려고 둔다.
 */
@Repository
public class CouponIssueRedisRepository {

	public enum IssueResult {
		OK, ALREADY_ISSUED, SOLD_OUT, NOT_IN_ISSUE_PERIOD,
		/** Redis에 쿠폰 정보가 없다. DB에서 읽어 올린 뒤 다시 시도한다. */
		INFO_NOT_LOADED
	}

	/** 쿠폰 관련 키는 모두 이 접두어로 시작한다 (개발 서버 시작 시 초기화, 테스트 정리에 쓴다). */
	public static final String KEY_PATTERN = "coupon:*";

	private static final String USERS_KEY = "coupon:%d:users";
	private static final String COUNT_KEY = "coupon:%d:count";
	private static final String INFO_KEY = "coupon:%d:info";
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

	/**
	 * 쿠폰 정보까지 Redis 안에서 확인하는 발급. DB를 읽지 않는다.
	 * KEYS[1] 받은 유저, KEYS[2] 발급 수량, KEYS[3] 쿠폰 정보 해시, ARGV[1] 유저 id, ARGV[2] 지금 시각(epoch 밀리초).
	 * 0 쿠폰 정보 없음, 1 발급, 2 이미 받음, 3 매진, 4 발급 기간 아님
	 */
	private static final RedisScript<Long> TRY_ISSUE_WITH_INFO = RedisScript.of("""
			local info = redis.call('HMGET', KEYS[3], 'total', 'start', 'end')
			if not info[1] then
				return 0
			end
			local now = tonumber(ARGV[2])
			if now < tonumber(info[2]) or now > tonumber(info[3]) then
				return 4
			end
			if redis.call('ZSCORE', KEYS[1], ARGV[1]) then
				return 2
			end
			local count = tonumber(redis.call('GET', KEYS[2]) or '0')
			if count >= tonumber(info[1]) then
				return 3
			end
			redis.call('INCR', KEYS[2])
			redis.call('ZADD', KEYS[1], ARGV[2], ARGV[1])
			return 1
			""", Long.class);

	/**
	 * 캐시 미스 때 DB에서 읽은 쿠폰 정보를 올린다. 이미 있으면 덮어쓰지 않는다.
	 * DB를 읽은 뒤 올리기 전에 쿠폰이 수정됐다면, 수정이 먼저 넣은 새 값을 옛 값으로 덮지 않기 위해서다.
	 * KEYS[1] 쿠폰 정보 해시, ARGV 총 수량, 시작, 종료
	 */
	private static final RedisScript<Long> LOAD_INFO = RedisScript.of("""
			if redis.call('EXISTS', KEYS[1]) == 1 then
				return 0
			end
			redis.call('HSET', KEYS[1], 'total', ARGV[1], 'start', ARGV[2], 'end', ARGV[3])
			return 1
			""", Long.class);

	/**
	 * 쿠폰 수정: 이미 발급된 수량보다 적은 총 수량이면 바꾸지 않고 0, 바꾸면 1.
	 * 확인과 저장을 한 스크립트로 묶어, 확인한 뒤 저장하기 전에 발급이 끼어들어 수량을 넘기는 일을 막는다.
	 * KEYS[1] 쿠폰 정보 해시, KEYS[2] 발급 수량, ARGV 총 수량, 시작, 종료
	 */
	private static final RedisScript<Long> UPDATE_INFO = RedisScript.of("""
			local count = tonumber(redis.call('GET', KEYS[2]) or '0')
			if count > tonumber(ARGV[1]) then
				return 0
			end
			redis.call('HSET', KEYS[1], 'total', ARGV[1], 'start', ARGV[2], 'end', ARGV[3])
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

	/** 3단계 방식: 총 수량을 호출하는 쪽(DB에서 읽은 값)이 넘겨준다. */
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

	/** 8단계 방식: 기간과 총 수량을 Redis의 쿠폰 정보로 확인한다. issuedAt이 판정 기준 시각이자 발급 시각이다. */
	public IssueResult tryIssue(Long couponId, Long userId, LocalDateTime issuedAt) {
		Long result = redisTemplate.execute(TRY_ISSUE_WITH_INFO,
				List.of(USERS_KEY.formatted(couponId), COUNT_KEY.formatted(couponId), INFO_KEY.formatted(couponId)),
				String.valueOf(userId), String.valueOf(toEpochMillis(issuedAt)));
		return switch (result == null ? -1 : result.intValue()) {
			case 0 -> IssueResult.INFO_NOT_LOADED;
			case 1 -> IssueResult.OK;
			case 2 -> IssueResult.ALREADY_ISSUED;
			case 4 -> IssueResult.NOT_IN_ISSUE_PERIOD;
			default -> IssueResult.SOLD_OUT;
		};
	}

	/** 쿠폰 정보가 없을 때만 올린다 (캐시 미스, 쿠폰 생성). 올렸으면 true. */
	public boolean loadInfo(CouponInfo info) {
		Long result = redisTemplate.execute(LOAD_INFO, List.of(INFO_KEY.formatted(info.couponId())), infoArgs(info));
		return result != null && result == 1;
	}

	/** 쿠폰 수정. 이미 발급된 수량보다 적은 총 수량이면 바꾸지 않고 false. */
	public boolean updateInfo(CouponInfo info) {
		Long result = redisTemplate.execute(UPDATE_INFO,
				List.of(INFO_KEY.formatted(info.couponId()), COUNT_KEY.formatted(info.couponId())), infoArgs(info));
		return result != null && result == 1;
	}

	/** 쿠폰 정보를 지운다 (무효화). 다음 발급 요청이 DB에서 다시 읽어 올린다. */
	public void deleteInfo(Long couponId) {
		redisTemplate.delete(INFO_KEY.formatted(couponId));
	}

	public Optional<CouponInfo> findInfo(Long couponId) {
		List<Object> values = redisTemplate.opsForHash().multiGet(INFO_KEY.formatted(couponId),
				List.of("total", "start", "end"));
		if (values.get(0) == null) {
			return Optional.empty();
		}
		return Optional.of(new CouponInfo(couponId, Integer.parseInt((String) values.get(0)),
				fromEpochMillis(Long.parseLong((String) values.get(1))),
				fromEpochMillis(Long.parseLong((String) values.get(2)))));
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

	private static Object[] infoArgs(CouponInfo info) {
		return new Object[] {String.valueOf(info.totalQuantity()), String.valueOf(toEpochMillis(info.issueStartAt())),
				String.valueOf(toEpochMillis(info.issueEndAt()))};
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
