package com.coupon.coupon;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.coupon.coupon.config.ServerIdHeaderFilter;
import com.coupon.coupon.controller.BenchController;
import java.util.List;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.ApplicationContext;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

/**
 * prod 프로필로 시작해 본다. bench 프로필을 함께 켜도 비교 API가 없고, API 문서가 꺼지며,
 * 빈 데이터베이스에서 db/schema.sql로 테이블을 만든 뒤 엔티티와 맞는지 확인(ddl-auto: validate)하고 시작해야 한다.
 * 다른 테스트가 Hibernate로 만든 테이블과 섞이지 않도록 따로 만든 데이터베이스(coupon_prod_test)를 쓰고 끝나면 지운다.
 * 데이터베이스를 만들 권한이 필요해서 root로 접속한다 (비밀번호는 .env의 MYSQL_ROOT_PASSWORD).
 */
@SpringBootTest(properties = {
		"spring.datasource.url=jdbc:mysql://${DB_HOST:localhost}:3306/" + ProdProfileTest.DATABASE
				+ "?createDatabaseIfNotExist=true&serverTimezone=Asia/Seoul",
		"spring.datasource.username=root",
		"spring.datasource.password=${MYSQL_ROOT_PASSWORD}",
		// 테스트 Kafka는 브로커가 1대다.
		"coupon.kafka.replicas=1"})
@ActiveProfiles({"prod", "bench"})
@AutoConfigureMockMvc
class ProdProfileTest {

	static final String DATABASE = "coupon_prod_test";

	private static JdbcTemplate jdbc;

	@Autowired
	private ApplicationContext context;

	@Autowired
	private MockMvc mockMvc;

	@Autowired
	void setJdbc(JdbcTemplate jdbcTemplate) {
		jdbc = jdbcTemplate;
	}

	@AfterAll
	static void dropDatabase() {
		jdbc.execute("DROP DATABASE IF EXISTS " + DATABASE);
	}

	@Test
	@DisplayName("빈 데이터베이스에서 schema.sql로 테이블을 만들고, 엔티티와 맞는지 확인을 통과해 시작한다")
	void schemaCreatedAndValidated() {
		List<String> tables = jdbc.queryForList(
				"SELECT table_name FROM information_schema.tables WHERE table_schema = ? ORDER BY table_name",
				String.class, DATABASE);
		assertThat(tables).containsExactly("coupon", "coupon_issue");
	}

	@Test
	@DisplayName("bench 프로필을 함께 켜도 비교 API와 서버 이름 헤더가 만들어지지 않는다")
	void benchDisabled() throws Exception {
		assertThat(context.getBeanNamesForType(BenchController.class)).isEmpty();
		assertThat(context.getBeanNamesForType(ServerIdHeaderFilter.class)).isEmpty();
		mockMvc.perform(post("/bench/coupons/1/issue?method=NO_LOCK")
						.contentType(MediaType.APPLICATION_JSON).content("{\"userId\":1}"))
				.andExpect(status().isNotFound());
	}

	@Test
	@DisplayName("API 문서(Swagger)가 꺼져 있다")
	void apiDocsDisabled() throws Exception {
		mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
		mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
	}
}
