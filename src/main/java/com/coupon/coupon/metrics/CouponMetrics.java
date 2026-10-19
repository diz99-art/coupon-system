package com.coupon.coupon.metrics;

import com.coupon.coupon.domain.exception.ErrorCode;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

/**
 * 쿠폰 업무 지표. Micrometer에 등록하면 /actuator/prometheus에 나오고, Prometheus가 주기적으로 가져간다.
 * 이름의 점(.)은 Prometheus에서 밑줄(_)로 바뀌고, 카운터에는 _total이 붙는다 (예: coupon_issue_success_total).
 * 서버마다 따로 세므로 Grafana에서 sum()으로 합친다.
 */
@Component
public class CouponMetrics {

	/** 발급 API가 거절하는 사유. 미리 0으로 등록해 두어야 한 번도 안 생긴 사유도 그래프에 0으로 나온다. */
	private static final List<ErrorCode> REJECT_REASONS = List.of(ErrorCode.COUPON_SOLD_OUT,
			ErrorCode.COUPON_ALREADY_ISSUED, ErrorCode.COUPON_NOT_IN_ISSUE_PERIOD, ErrorCode.COUPON_NOT_FOUND,
			ErrorCode.COUPON_ISSUE_BUSY);

	private final MeterRegistry registry;
	private final Counter issueSuccess;
	private final Map<ErrorCode, Counter> issueRejected = new EnumMap<>(ErrorCode.class);
	private final Timer saveDelay;
	private final Counter reconcileSavedToDb;
	private final Counter reconcileRestoredToRedis;
	private final Timer reconcileDuration;

	public CouponMetrics(MeterRegistry registry) {
		this.registry = registry;
		issueSuccess = Counter.builder("coupon.issue.success")
				.description("발급 확정 수 (Redis 판정을 통과하고 Kafka 전송까지 마친 요청)")
				.register(registry);
		for (ErrorCode reason : REJECT_REASONS) {
			issueRejected.put(reason, rejectedCounter(reason));
		}
		// 히스토그램(구간별 개수)으로 내보내야 Grafana에서 서버 2대를 합친 p95를 계산할 수 있다.
		saveDelay = Timer.builder("coupon.issue.save.delay")
				.description("발급 확정부터 Kafka 컨슈머가 DB에 저장할 때까지 걸린 시간")
				.publishPercentileHistogram()
				.minimumExpectedValue(Duration.ofMillis(1))
				.maximumExpectedValue(Duration.ofMinutes(1))
				.register(registry);
		reconcileSavedToDb = reconcileCounter("db");
		reconcileRestoredToRedis = reconcileCounter("redis");
		reconcileDuration = Timer.builder("coupon.reconcile.duration")
				.description("대사 작업 한 번에 걸린 시간. 개수(_count)로 어느 서버가 실행했는지 본다")
				.register(registry);
	}

	public void issued() {
		issueSuccess.increment();
	}

	public void rejected(ErrorCode reason) {
		issueRejected.computeIfAbsent(reason, this::rejectedCounter).increment();
	}

	public void savedAfter(Duration delay) {
		saveDelay.record(delay);
	}

	public void reconciled(int savedToDb, int restoredToRedis, Duration took) {
		reconcileSavedToDb.increment(savedToDb);
		reconcileRestoredToRedis.increment(restoredToRedis);
		reconcileDuration.record(took);
	}

	private Counter rejectedCounter(ErrorCode reason) {
		return Counter.builder("coupon.issue.rejected")
				.description("발급 거절 수 (사유별)")
				.tag("reason", reason.name())
				.register(registry);
	}

	/** target: 대사 작업이 빠진 발급을 채운 곳. db = Redis에만 있던 발급을 DB에 저장, redis = DB에만 있던 발급을 Redis에 복구 */
	private Counter reconcileCounter(String target) {
		return Counter.builder("coupon.reconcile.corrected")
				.description("대사 작업이 보정한 발급 건수")
				.tag("target", target)
				.register(registry);
	}
}
