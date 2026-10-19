package com.coupon.coupon.metrics;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.coupon.coupon.IntegrationTest;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.exception.CouponAlreadyIssuedException;
import com.coupon.coupon.domain.exception.CouponSoldOutException;
import com.coupon.coupon.messaging.CouponIssueProducer;
import com.coupon.coupon.service.KafkaCouponIssueService;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.LocalDateTime;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

/** 발급 결과가 커스텀 지표(성공 수, 사유별 거절 수)로 세어지는지 확인한다. Kafka 전송은 가짜로 바꾼다. */
@SpringBootTest
class CouponMetricsTest extends IntegrationTest {

	@Autowired
	private KafkaCouponIssueService couponIssueService;

	@Autowired
	private MeterRegistry meterRegistry;

	@MockitoBean
	private CouponIssueProducer couponIssueProducer;

	@Test
	@DisplayName("발급 성공은 success, 매진과 중복은 거절 사유별로 센다")
	void countsIssueResults() {
		LocalDateTime now = LocalDateTime.now();
		Coupon coupon = couponRepository.save(new Coupon("지표 쿠폰", 1, now.minusHours(1), now.plusHours(1)));
		double success = count("coupon.issue.success", null);
		double soldOut = count("coupon.issue.rejected", "COUPON_SOLD_OUT");
		double duplicated = count("coupon.issue.rejected", "COUPON_ALREADY_ISSUED");

		couponIssueService.issue(coupon.getId(), 1L);
		assertThatThrownBy(() -> couponIssueService.issue(coupon.getId(), 1L))
				.isInstanceOf(CouponAlreadyIssuedException.class);
		assertThatThrownBy(() -> couponIssueService.issue(coupon.getId(), 2L))
				.isInstanceOf(CouponSoldOutException.class);
		assertThatThrownBy(() -> couponIssueService.issue(coupon.getId(), 3L))
				.isInstanceOf(CouponSoldOutException.class);

		// 지표는 애플리케이션 전체에서 누적되므로 테스트 전후 차이로 확인한다.
		assertThat(count("coupon.issue.success", null) - success).isEqualTo(1);
		assertThat(count("coupon.issue.rejected", "COUPON_ALREADY_ISSUED") - duplicated).isEqualTo(1);
		assertThat(count("coupon.issue.rejected", "COUPON_SOLD_OUT") - soldOut).isEqualTo(2);
	}

	private double count(String name, String reason) {
		var search = meterRegistry.find(name);
		if (reason != null) {
			search = search.tag("reason", reason);
		}
		return search.counter().count();
	}
}
