package com.coupon.coupon.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasItem;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coupon.coupon.IntegrationTest;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.domain.CouponIssue;
import com.coupon.coupon.messaging.CouponIssueProducer;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * API와 에러 응답 형식을 확인한다. Kafka 전송은 가짜(Mockito)로 바꿔서 메시지를 실제로 보내지 않는다.
 * 그래서 발급은 Redis까지만 반영되고, 내 발급 내역은 DB에 직접 넣어서 확인한다.
 */
@SpringBootTest
@AutoConfigureMockMvc
class CouponApiTest extends IntegrationTest {

	private static final DateTimeFormatter FORMAT = DateTimeFormatter.ISO_LOCAL_DATE_TIME;

	@Autowired
	private MockMvc mockMvc;

	@MockitoBean
	private CouponIssueProducer couponIssueProducer;

	@Nested
	@DisplayName("POST /coupons 쿠폰 생성")
	class Create {

		@Test
		@DisplayName("201과 Location 헤더, 생성된 쿠폰을 돌려준다")
		void created() throws Exception {
			LocalDateTime start = LocalDateTime.of(2026, 10, 20, 10, 0);
			createCoupon("선착순 쿠폰", 100, start, start.plusHours(1))
					.andExpect(status().isCreated())
					.andExpect(header().string("Location", containsString("/coupons/")))
					.andExpect(jsonPath("$.name").value("선착순 쿠폰"))
					.andExpect(jsonPath("$.totalQuantity").value(100))
					.andExpect(jsonPath("$.remainingQuantity").value(100))
					.andExpect(jsonPath("$.issueStartAt").value("2026-10-20T10:00:00"));
			assertThat(couponRepository.count()).isEqualTo(1);
		}

		@Test
		@DisplayName("수량이 0 이하이면 400과 필드 에러")
		void quantityMustBePositive() throws Exception {
			LocalDateTime start = LocalDateTime.now();
			createCoupon("쿠폰", 0, start, start.plusHours(1))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
					.andExpect(jsonPath("$.errors[0].field").value("totalQuantity"))
					.andExpect(jsonPath("$.errors[0].message").value("총 수량은 1 이상이어야 합니다."));
		}

		@Test
		@DisplayName("종료 시각이 시작 시각보다 빠르면 400")
		void endMustBeAfterStart() throws Exception {
			LocalDateTime start = LocalDateTime.now();
			createCoupon("쿠폰", 10, start, start.minusMinutes(1))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("issueEndAfterStart"))
					.andExpect(jsonPath("$.errors[0].message").value("발급 종료 시각은 시작 시각보다 늦어야 합니다."));
		}

		@Test
		@DisplayName("이름이 비었고 시각이 없으면 틀린 필드를 모두 알려준다")
		void reportsAllInvalidFields() throws Exception {
			mockMvc.perform(post("/coupons").contentType(MediaType.APPLICATION_JSON)
							.content("{\"name\":\" \",\"totalQuantity\":10}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors.length()").value(3))
					.andExpect(jsonPath("$.errors[*].field", hasItem("name")))
					.andExpect(jsonPath("$.errors[*].field", hasItem("issueStartAt")))
					.andExpect(jsonPath("$.errors[*].field", hasItem("issueEndAt")));
		}

		@Test
		@DisplayName("날짜 형식이 틀리면 400")
		void unreadableBody() throws Exception {
			mockMvc.perform(post("/coupons").contentType(MediaType.APPLICATION_JSON)
							.content("{\"name\":\"쿠폰\",\"totalQuantity\":10,\"issueStartAt\":\"내일\",\"issueEndAt\":\"모레\"}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"))
					.andExpect(jsonPath("$.errors").isEmpty());
		}
	}

	@Nested
	@DisplayName("GET /coupons/{id} 쿠폰 조회")
	class Get {

		@Test
		@DisplayName("남은 수량은 Redis 카운터 기준이다")
		void remainingFromRedis() throws Exception {
			Coupon coupon = openCoupon(100);
			for (long userId = 1; userId <= 3; userId++) {
				issue(coupon.getId(), userId).andExpect(status().isAccepted());
			}

			mockMvc.perform(get("/coupons/{id}", coupon.getId()))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.issuedQuantity").value(3))
					.andExpect(jsonPath("$.remainingQuantity").value(97));
		}

		@Test
		@DisplayName("없는 쿠폰은 404 COUPON_NOT_FOUND")
		void notFound() throws Exception {
			mockMvc.perform(get("/coupons/{id}", 999_999))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").value("COUPON_NOT_FOUND"))
					.andExpect(jsonPath("$.message").value("쿠폰을 찾을 수 없습니다."));
		}

		@Test
		@DisplayName("id 자리에 숫자가 아닌 값이 오면 400")
		void invalidId() throws Exception {
			mockMvc.perform(get("/coupons/{id}", "abc"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.code").value("INVALID_REQUEST"));
		}
	}

	@Nested
	@DisplayName("PUT /coupons/{id} 쿠폰 수정")
	class Update {

		@Test
		@DisplayName("200과 바뀐 쿠폰, 바로 다음 발급부터 새 수량이 적용된다")
		void updated() throws Exception {
			Coupon coupon = openCoupon(1);
			issue(coupon.getId(), 1L).andExpect(status().isAccepted());
			expectError(issue(coupon.getId(), 2L), 409, "COUPON_SOLD_OUT");

			updateCoupon(coupon.getId(), "늘린 쿠폰", 2, coupon.getIssueStartAt(), coupon.getIssueEndAt())
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.name").value("늘린 쿠폰"))
					.andExpect(jsonPath("$.totalQuantity").value(2))
					.andExpect(jsonPath("$.remainingQuantity").value(1));
			issue(coupon.getId(), 2L).andExpect(status().isAccepted());
		}

		@Test
		@DisplayName("이미 발급된 수량보다 적게 줄이면 409 COUPON_QUANTITY_BELOW_ISSUED")
		void belowIssued() throws Exception {
			Coupon coupon = openCoupon(10);
			issue(coupon.getId(), 1L).andExpect(status().isAccepted());
			issue(coupon.getId(), 2L).andExpect(status().isAccepted());
			expectError(updateCoupon(coupon.getId(), "쿠폰", 1, coupon.getIssueStartAt(), coupon.getIssueEndAt()),
					409, "COUPON_QUANTITY_BELOW_ISSUED");
		}

		@Test
		@DisplayName("없는 쿠폰이면 404")
		void notFound() throws Exception {
			LocalDateTime now = LocalDateTime.now();
			expectError(updateCoupon(999_999L, "쿠폰", 1, now, now.plusHours(1)), 404, "COUPON_NOT_FOUND");
		}
	}

	@Nested
	@DisplayName("POST /coupons/{id}/issue 쿠폰 발급")
	class Issue {

		@Test
		@DisplayName("발급되면 202")
		void accepted() throws Exception {
			Coupon coupon = openCoupon(10);
			issue(coupon.getId(), 1L)
					.andExpect(status().isAccepted())
					.andExpect(jsonPath("$.couponId").value(coupon.getId()))
					.andExpect(jsonPath("$.userId").value(1));
		}

		@Test
		@DisplayName("같은 유저가 다시 요청하면 409 COUPON_ALREADY_ISSUED")
		void alreadyIssued() throws Exception {
			Coupon coupon = openCoupon(10);
			issue(coupon.getId(), 1L).andExpect(status().isAccepted());
			expectError(issue(coupon.getId(), 1L), 409, "COUPON_ALREADY_ISSUED");
		}

		@Test
		@DisplayName("수량을 다 쓰면 409 COUPON_SOLD_OUT")
		void soldOut() throws Exception {
			Coupon coupon = openCoupon(1);
			issue(coupon.getId(), 1L).andExpect(status().isAccepted());
			expectError(issue(coupon.getId(), 2L), 409, "COUPON_SOLD_OUT");
		}

		@Test
		@DisplayName("발급 기간 전이면 409 COUPON_NOT_IN_ISSUE_PERIOD")
		void notInPeriod() throws Exception {
			LocalDateTime now = LocalDateTime.now();
			Coupon coupon = couponRepository.save(new Coupon("내일 쿠폰", 10, now.plusDays(1), now.plusDays(2)));
			expectError(issue(coupon.getId(), 1L), 409, "COUPON_NOT_IN_ISSUE_PERIOD");
		}

		@Test
		@DisplayName("없는 쿠폰이면 404 COUPON_NOT_FOUND")
		void notFound() throws Exception {
			expectError(issue(999_999L, 1L), 404, "COUPON_NOT_FOUND");
		}

		@Test
		@DisplayName("userId가 없으면 400")
		void userIdRequired() throws Exception {
			Coupon coupon = openCoupon(10);
			mockMvc.perform(post("/coupons/{id}/issue", coupon.getId()).contentType(MediaType.APPLICATION_JSON).content("{}"))
					.andExpect(status().isBadRequest())
					.andExpect(jsonPath("$.errors[0].field").value("userId"));
		}

		@Test
		@DisplayName("Kafka 전송이 실패하면 503과 Retry-After, Redis 자리는 되돌린다")
		void busyWhenKafkaFails() throws Exception {
			Coupon coupon = openCoupon(10);
			doThrow(new IllegalStateException("Kafka 장애 흉내")).when(couponIssueProducer).send(any());

			expectError(issue(coupon.getId(), 1L), 503, "COUPON_ISSUE_BUSY")
					.andExpect(header().string("Retry-After", "1"));
			assertThat(couponIssueRedisRepository.issuedCount(coupon.getId())).isZero();
			assertThat(couponIssueRedisRepository.issuedUserIds(coupon.getId())).isEmpty();
		}
	}

	@Nested
	@DisplayName("GET /users/{userId}/coupons 내 발급 내역")
	class UserCoupons {

		@Test
		@DisplayName("최근 발급 순으로 쿠폰 이름과 발급 시각을 돌려준다")
		void newestFirst() throws Exception {
			LocalDateTime now = LocalDateTime.now().truncatedTo(ChronoUnit.SECONDS);
			Coupon first = couponRepository.save(new Coupon("첫 쿠폰", 10, now.minusDays(1), now.plusDays(1)));
			Coupon second = couponRepository.save(new Coupon("둘째 쿠폰", 10, now.minusDays(1), now.plusDays(1)));
			couponIssueRepository.save(new CouponIssue(first.getId(), 1L, now.minusMinutes(10)));
			couponIssueRepository.save(new CouponIssue(second.getId(), 1L, now.minusMinutes(1)));
			couponIssueRepository.save(new CouponIssue(second.getId(), 2L, now));

			mockMvc.perform(get("/users/{userId}/coupons", 1))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(2))
					.andExpect(jsonPath("$[0].couponName").value("둘째 쿠폰"))
					.andExpect(jsonPath("$[0].issuedAt").value(now.minusMinutes(1).format(FORMAT)))
					.andExpect(jsonPath("$[1].couponName").value("첫 쿠폰"));
		}

		@Test
		@DisplayName("받은 쿠폰이 없으면 빈 배열")
		void empty() throws Exception {
			mockMvc.perform(get("/users/{userId}/coupons", 42))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.length()").value(0));
		}
	}

	@Nested
	@DisplayName("그 밖의 응답")
	class Others {

		@Test
		@DisplayName("없는 주소도 같은 에러 형식으로 404")
		void unknownPath() throws Exception {
			mockMvc.perform(get("/no-such-path"))
					.andExpect(status().isNotFound())
					.andExpect(jsonPath("$.code").exists())
					.andExpect(jsonPath("$.message").exists());
		}

		@Test
		@DisplayName("비교용 API는 bench 프로필이 아니면 없다")
		void benchApiHidden() throws Exception {
			mockMvc.perform(post("/bench/coupons/{id}/issue", 1).param("method", "NO_LOCK")
							.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
					.andExpect(status().isNotFound());
			mockMvc.perform(get("/v3/api-docs"))
					.andExpect(jsonPath("$.paths['/bench/coupons/{couponId}/issue']").doesNotExist());
		}

		@Test
		@DisplayName("Swagger 문서와 화면이 열린다")
		void swagger() throws Exception {
			mockMvc.perform(get("/v3/api-docs"))
					.andExpect(status().isOk())
					.andExpect(jsonPath("$.paths['/coupons']").exists())
					.andExpect(jsonPath("$.paths['/coupons/{couponId}/issue']").exists())
					.andExpect(jsonPath("$.paths['/users/{userId}/coupons']").exists());
			mockMvc.perform(get("/swagger-ui/index.html"))
					.andExpect(status().isOk());
		}
	}

	private Coupon openCoupon(int totalQuantity) {
		LocalDateTime now = LocalDateTime.now();
		return couponRepository.save(new Coupon("쿠폰", totalQuantity, now.minusHours(1), now.plusHours(1)));
	}

	private ResultActions createCoupon(String name, int quantity, LocalDateTime start, LocalDateTime end) throws Exception {
		String body = "{\"name\":\"%s\",\"totalQuantity\":%d,\"issueStartAt\":\"%s\",\"issueEndAt\":\"%s\"}"
				.formatted(name, quantity, start.format(FORMAT), end.format(FORMAT));
		return mockMvc.perform(post("/coupons").contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions updateCoupon(Long couponId, String name, int quantity, LocalDateTime start, LocalDateTime end)
			throws Exception {
		String body = "{\"name\":\"%s\",\"totalQuantity\":%d,\"issueStartAt\":\"%s\",\"issueEndAt\":\"%s\"}"
				.formatted(name, quantity, start.format(FORMAT), end.format(FORMAT));
		return mockMvc.perform(put("/coupons/{id}", couponId).contentType(MediaType.APPLICATION_JSON).content(body));
	}

	private ResultActions issue(Long couponId, Long userId) throws Exception {
		return mockMvc.perform(post("/coupons/{id}/issue", couponId)
				.contentType(MediaType.APPLICATION_JSON)
				.content("{\"userId\":%d}".formatted(userId)));
	}

	private ResultActions expectError(ResultActions result, int status, String code) throws Exception {
		return result.andExpect(status().is(status))
				.andExpect(jsonPath("$.code").value(code))
				.andExpect(jsonPath("$.message").isNotEmpty())
				.andExpect(jsonPath("$.errors").isArray());
	}
}
