package com.coupon.coupon.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coupon.coupon.IntegrationTest;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.messaging.CouponIssueProducer;
import java.time.LocalDateTime;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

/** bench 프로필에서 방식마다 발급과 결과 조회가 되는지 확인한다. Kafka 전송은 가짜로 바꾼다. */
@SpringBootTest(properties = "spring.profiles.active=test,bench")
@AutoConfigureMockMvc
class BenchControllerTest extends IntegrationTest {

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponIssueProducer couponIssueProducer;

	@ParameterizedTest
	@EnumSource(BenchController.Method.class)
	void issueWithEachMethod(BenchController.Method method) throws Exception {
		LocalDateTime now = LocalDateTime.now();
		Coupon coupon = couponRepository.save(new Coupon("비교 쿠폰", 2, now.minusHours(1), now.plusHours(1)));

		for (long userId = 1; userId <= 2; userId++) {
			mockMvc.perform(post("/bench/coupons/{id}/issue", coupon.getId()).param("method", method.name())
							.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":%d}".formatted(userId)))
					.andExpect(status().isOk())
					.andExpect(header().exists("X-Server-Id"))
					.andExpect(jsonPath("$.method").value(method.name()));
		}
		mockMvc.perform(post("/bench/coupons/{id}/issue", coupon.getId()).param("method", method.name())
						.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":3}"))
				.andExpect(status().isConflict())
				.andExpect(jsonPath("$.code").value("COUPON_SOLD_OUT"));

		// Redis 방식은 Redis 카운터, DB 방식은 DB 기록으로 2건이 남는다 (Kafka 방식은 전송을 가짜로 바꿔 DB 기록 없음).
		boolean redisBased = method == BenchController.Method.REDIS_LUA || method == BenchController.Method.REDIS_KAFKA;
		mockMvc.perform(get("/bench/coupons/{id}/result", coupon.getId()))
				.andExpect(status().isOk())
				.andExpect(jsonPath(redisBased ? "$.redisCount" : "$.issueRows").value(2));
	}
}
