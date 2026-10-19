package com.coupon.coupon.controller;

import com.coupon.coupon.controller.dto.IssueRequest;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.service.CouponService;
import com.coupon.coupon.service.KafkaCouponIssueService;
import com.coupon.coupon.service.NoLockCouponIssueService;
import com.coupon.coupon.service.OptimisticLockCouponIssueService;
import com.coupon.coupon.service.PessimisticLockCouponIssueService;
import com.coupon.coupon.service.RedisCounterCouponIssueService;
import com.coupon.coupon.service.RedissonLockCouponIssueService;
import com.coupon.coupon.service.SynchronizedCouponIssueService;
import com.coupon.coupon.service.SynchronizedOutsideTransactionCouponIssueService;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import jakarta.validation.Valid;
import java.util.EnumMap;
import java.util.Map;
import java.util.function.BiConsumer;
import org.springframework.context.annotation.Profile;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 부하 테스트용 비교 API. bench 프로필에서만 빈이 만들어지므로 운영(local, docker 단독)에서는 주소 자체가 없다(404).
 * 발급 방식을 골라 같은 요청으로 비교하고, 결과(DB 발급 건수, Redis 카운터)를 확인한다.
 */
@Tag(name = "비교 (bench 프로필 전용)", description = "발급 방식별 부하 테스트")
@Profile("bench")
@RestController
@RequestMapping("/bench")
public class BenchController {

	public enum Method {
		NO_LOCK,
		SYNCHRONIZED_IN_TRANSACTION,
		SYNCHRONIZED,
		PESSIMISTIC_LOCK,
		OPTIMISTIC_LOCK,
		REDISSON_LOCK,
		REDIS_LUA,
		REDIS_KAFKA
	}

	private final Map<Method, BiConsumer<Long, Long>> issuers = new EnumMap<>(Method.class);
	private final CouponService couponService;
	private final CouponIssueRepository couponIssueRepository;
	private final CouponIssueRedisRepository couponIssueRedisRepository;

	public BenchController(
			NoLockCouponIssueService noLock,
			SynchronizedCouponIssueService synchronizedInTransaction,
			SynchronizedOutsideTransactionCouponIssueService synchronizedOutside,
			PessimisticLockCouponIssueService pessimisticLock,
			OptimisticLockCouponIssueService optimisticLock,
			RedissonLockCouponIssueService redissonLock,
			RedisCounterCouponIssueService redisLua,
			KafkaCouponIssueService redisKafka,
			CouponService couponService,
			CouponIssueRepository couponIssueRepository,
			CouponIssueRedisRepository couponIssueRedisRepository) {
		issuers.put(Method.NO_LOCK, noLock::issue);
		issuers.put(Method.SYNCHRONIZED_IN_TRANSACTION, synchronizedInTransaction::issue);
		issuers.put(Method.SYNCHRONIZED, synchronizedOutside::issue);
		issuers.put(Method.PESSIMISTIC_LOCK, pessimisticLock::issue);
		issuers.put(Method.OPTIMISTIC_LOCK, optimisticLock::issue);
		issuers.put(Method.REDISSON_LOCK, redissonLock::issue);
		issuers.put(Method.REDIS_LUA, redisLua::issue);
		issuers.put(Method.REDIS_KAFKA, redisKafka::issue);
		this.couponService = couponService;
		this.couponIssueRepository = couponIssueRepository;
		this.couponIssueRedisRepository = couponIssueRedisRepository;
	}

	@Operation(summary = "방식을 골라 발급", description = "성공하면 200. 실패 응답은 운영 API와 같다 (409 매진·중복 등).")
	@PostMapping("/coupons/{couponId}/issue")
	public IssuedResponse issue(@PathVariable Long couponId, @RequestParam Method method,
			@Valid @RequestBody IssueRequest request) {
		issuers.get(method).accept(couponId, request.userId());
		return new IssuedResponse(couponId, request.userId(), method);
	}

	@Operation(summary = "발급 결과", description = "DB 발급 기록 수, DB의 issuedQuantity 컬럼, Redis 카운터")
	@GetMapping("/coupons/{couponId}/result")
	public ResultResponse result(@PathVariable Long couponId) {
		return new ResultResponse(couponId,
				couponService.get(couponId).getTotalQuantity(),
				couponIssueRepository.countByCouponId(couponId),
				couponService.get(couponId).getIssuedQuantity(),
				couponIssueRedisRepository.issuedCount(couponId));
	}

	public record IssuedResponse(Long couponId, Long userId, Method method) {
	}

	public record ResultResponse(Long couponId, int totalQuantity, long issueRows, int dbIssuedQuantity,
			long redisCount) {
	}
}
