package com.coupon.coupon.config;

import com.coupon.coupon.repository.CouponIssueRedisRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

/**
 * 개발 환경(local)에서는 서버를 켤 때마다 DB 테이블을 새로 만든다(ddl-auto: create).
 * Redis의 쿠폰 키도 같이 지워야 새 DB와 어긋나지 않는다. 지우지 않으면 대사 작업이 지난번 발급을 새 DB에 채워 넣는다.
 * 빈이 만들어지는 시점에 실행되므로 Kafka 리스너와 스케줄러가 시작되기 전에 끝난다.
 */
@Slf4j
@Component
@Profile("local")
@RequiredArgsConstructor
public class LocalRedisInitializer implements InitializingBean {

	private final CouponIssueRedisRepository couponIssueRedisRepository;

	@Override
	public void afterPropertiesSet() {
		long deleted = couponIssueRedisRepository.deleteAll();
		log.info("local 프로필: Redis 쿠폰 키 {}개를 지웠다", deleted);
	}
}
