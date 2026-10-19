package com.coupon.coupon;

import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;

/**
 * DB와 Redis를 쓰는 테스트의 부모. 테스트마다 시작 전과 끝난 뒤에 발급 기록, 쿠폰, Redis 쿠폰 키를 지운다.
 * 시작 전: 지난 테스트가 중간에 죽어 남긴 데이터가 있어도 영향을 받지 않는다.
 * 끝난 뒤: 다음 테스트 클래스(다른 스프링 컨텍스트)나 개발 서버에 데이터를 남기지 않는다.
 * 하위 클래스의 @BeforeEach는 이 정리가 끝난 뒤에 실행된다.
 */
public abstract class IntegrationTest {

	@Autowired
	protected CouponRepository couponRepository;

	@Autowired
	protected CouponIssueRepository couponIssueRepository;

	@Autowired
	protected CouponIssueRedisRepository couponIssueRedisRepository;

	@BeforeEach
	void cleanBefore() {
		cleanUp();
	}

	@AfterEach
	void cleanAfter() {
		cleanUp();
	}

	private void cleanUp() {
		couponIssueRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		couponIssueRedisRepository.deleteAll();
	}
}
