package com.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coupon.coupon.IntegrationTest;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.exception.CouponNotFoundException;
import com.coupon.coupon.domain.exception.CouponNotInIssuePeriodException;
import com.coupon.coupon.domain.exception.CouponQuantityBelowIssuedException;
import com.coupon.coupon.domain.exception.CouponSoldOutException;
import com.coupon.coupon.messaging.CouponIssueProducer;
import com.coupon.coupon.repository.CouponInfo;
import jakarta.persistence.EntityManagerFactory;
import java.time.Duration;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import org.hibernate.SessionFactory;
import org.hibernate.stat.Statistics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 8단계: 발급 판정에 쓰는 쿠폰 정보를 Redis에 두는 캐시.
 * 실행된 SQL 수는 Hibernate 통계(generate_statistics)의 prepareStatementCount로 센다. Kafka 전송은 가짜로 바꾼다.
 */
@SpringBootTest(properties = {"spring.jpa.properties.hibernate.generate_statistics=true",
		"coupon.cache.not-found-ttl=1s"})
class CouponInfoCacheTest extends IntegrationTest {

	@Autowired
	private CouponService couponService;

	@Autowired
	private KafkaCouponIssueService couponIssueService;

	@Autowired
	private EntityManagerFactory entityManagerFactory;

	@Autowired
	private TransactionTemplate transactionTemplate;

	@MockitoBean
	private CouponIssueProducer couponIssueProducer;

	private Statistics statistics;
	private LocalDateTime now;

	@BeforeEach
	void setUp() {
		statistics = entityManagerFactory.unwrap(SessionFactory.class).getStatistics();
		now = LocalDateTime.now().truncatedTo(ChronoUnit.MILLIS);
	}

	@Test
	@DisplayName("쿠폰을 만들면 커밋 뒤 Redis에 쿠폰 정보가 올라간다")
	void createWritesInfo() {
		Coupon coupon = couponService.create("쿠폰", 100, now.minusHours(1), now.plusHours(1));

		assertThat(couponIssueRedisRepository.findInfo(coupon.getId()))
				.contains(new CouponInfo(coupon.getId(), 100, now.minusHours(1), now.plusHours(1)));
	}

	@Test
	@DisplayName("캐시 적중: 발급 요청이 SQL을 하나도 실행하지 않는다")
	void cacheHitRunsNoSql() {
		Coupon coupon = couponService.create("쿠폰", 100, now.minusHours(1), now.plusHours(1));

		statistics.clear();
		couponIssueService.issue(coupon.getId(), 1L);
		couponIssueService.issue(coupon.getId(), 2L);

		assertThat(statistics.getPrepareStatementCount()).isZero();
		assertThat(couponIssueRedisRepository.issuedCount(coupon.getId())).isEqualTo(2);
	}

	@Test
	@DisplayName("캐시 미스: 쿠폰 정보가 없으면 DB에서 한 번 읽어 올리고, 다음 요청부터는 읽지 않는다")
	void cacheMissLoadsOnce() {
		// Redis 쿠폰 정보 없이 DB에만 있는 쿠폰 (Redis 데이터가 사라진 상황)
		Coupon coupon = couponRepository.save(new Coupon("쿠폰", 100, now.minusHours(1), now.plusHours(1)));
		assertThat(couponIssueRedisRepository.findInfo(coupon.getId())).isEmpty();

		statistics.clear();
		couponIssueService.issue(coupon.getId(), 1L);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(couponIssueRedisRepository.findInfo(coupon.getId())).isPresent();

		statistics.clear();
		couponIssueService.issue(coupon.getId(), 2L);
		assertThat(statistics.getPrepareStatementCount()).isZero();
	}

	@Test
	@DisplayName("캐시 관통 방지: 없는 쿠폰은 한 번만 DB에서 확인하고, \"없음\" 표시가 남은 동안은 DB를 읽지 않고 404")
	void notFoundIsCachedBriefly() throws InterruptedException {
		statistics.clear();
		assertThatThrownBy(() -> couponIssueService.issue(999_999L, 1L)).isInstanceOf(CouponNotFoundException.class);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
		assertThat(couponIssueRedisRepository.isMarkedNotFound(999_999L)).isTrue();
		assertThat(couponIssueRedisRepository.findInfo(999_999L)).isEmpty();

		statistics.clear();
		for (long userId = 2; userId <= 10; userId++) {
			long user = userId;
			assertThatThrownBy(() -> couponIssueService.issue(999_999L, user))
					.isInstanceOf(CouponNotFoundException.class);
		}
		assertThat(statistics.getPrepareStatementCount()).isZero();

		// 표시는 TTL(이 테스트에서는 1초)이 지나면 사라지고, 다음 요청은 다시 DB에서 확인한다.
		Thread.sleep(1_200);
		statistics.clear();
		assertThatThrownBy(() -> couponIssueService.issue(999_999L, 1L)).isInstanceOf(CouponNotFoundException.class);
		assertThat(statistics.getPrepareStatementCount()).isEqualTo(1);
	}

	@Test
	@DisplayName("\"없음\" 표시가 남아 있어도 쿠폰 정보가 올라가면(쿠폰 생성) 표시를 지우고 바로 발급된다")
	void loadingInfoClearsNotFoundMark() {
		// "없음"으로 확인된 직후 그 id의 쿠폰이 만들어진 상황
		Coupon coupon = couponRepository.save(new Coupon("쿠폰", 10, now.minusHours(1), now.plusHours(1)));
		couponIssueRedisRepository.markNotFound(coupon.getId(), Duration.ofMinutes(1));
		assertThatThrownBy(() -> couponIssueService.issue(coupon.getId(), 1L))
				.isInstanceOf(CouponNotFoundException.class);

		// 쿠폰 생성이 커밋 뒤에 하는 일
		couponIssueRedisRepository.loadInfo(CouponInfo.from(coupon));

		assertThat(couponIssueRedisRepository.isMarkedNotFound(coupon.getId())).isFalse();
		couponIssueService.issue(coupon.getId(), 1L);
	}

	@Test
	@DisplayName("쿠폰을 수정하면 Redis 쿠폰 정보도 바뀌어 바로 다음 발급부터 적용된다")
	void updateRefreshesInfo() {
		Coupon coupon = couponService.create("쿠폰", 1, now.minusHours(1), now.plusHours(1));
		couponIssueService.issue(coupon.getId(), 1L);
		assertThatThrownBy(() -> couponIssueService.issue(coupon.getId(), 2L))
				.isInstanceOf(CouponSoldOutException.class);

		// 수량 1 → 2
		couponService.update(coupon.getId(), "쿠폰", 2, now.minusHours(1), now.plusHours(1));
		couponIssueService.issue(coupon.getId(), 2L);

		// 기간을 내일로 미룬다
		couponService.update(coupon.getId(), "쿠폰", 10, now.plusDays(1), now.plusDays(2));
		assertThatThrownBy(() -> couponIssueService.issue(coupon.getId(), 3L))
				.isInstanceOf(CouponNotInIssuePeriodException.class);

		assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTotalQuantity()).isEqualTo(10);
		assertThat(couponIssueRedisRepository.findInfo(coupon.getId()))
				.contains(new CouponInfo(coupon.getId(), 10, now.plusDays(1), now.plusDays(2)));
	}

	@Test
	@DisplayName("이미 발급된 수량보다 적게 줄이면 거절하고 DB와 Redis 모두 그대로 둔다")
	void updateBelowIssuedIsRejected() {
		Coupon coupon = couponService.create("쿠폰", 10, now.minusHours(1), now.plusHours(1));
		couponIssueService.issue(coupon.getId(), 1L);
		couponIssueService.issue(coupon.getId(), 2L);

		assertThatThrownBy(() -> couponService.update(coupon.getId(), "쿠폰", 1, now.minusHours(1), now.plusHours(1)))
				.isInstanceOf(CouponQuantityBelowIssuedException.class);

		assertThat(couponRepository.findById(coupon.getId()).orElseThrow().getTotalQuantity()).isEqualTo(10);
		assertThat(couponIssueRedisRepository.findInfo(coupon.getId()).orElseThrow().totalQuantity()).isEqualTo(10);
	}

	@Test
	@DisplayName("수정 트랜잭션이 롤백되면 Redis 쿠폰 정보를 지우고, 다음 발급이 DB의 원래 값을 다시 읽는다")
	void rollbackDeletesInfo() {
		Coupon coupon = couponService.create("쿠폰", 1, now.minusHours(1), now.plusHours(1));

		// Redis는 새 값(수량 5)으로 바뀐 뒤 DB 커밋이 실패한 상황
		transactionTemplate.executeWithoutResult(status -> {
			couponService.update(coupon.getId(), "쿠폰", 5, now.minusHours(1), now.plusHours(1));
			status.setRollbackOnly();
		});

		assertThat(couponIssueRedisRepository.findInfo(coupon.getId())).isEmpty();
		couponIssueService.issue(coupon.getId(), 1L);
		assertThat(couponIssueRedisRepository.findInfo(coupon.getId()).orElseThrow().totalQuantity()).isEqualTo(1);
		assertThatThrownBy(() -> couponIssueService.issue(coupon.getId(), 2L))
				.isInstanceOf(CouponSoldOutException.class);
	}
}
