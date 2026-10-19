package com.coupon.coupon.messaging;

import com.coupon.coupon.service.CouponIssueSaver;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

/**
 * 발급 메시지를 받아 DB에 저장한다. 저장 중 예외가 나면 KafkaConfig의 에러 핸들러가
 * 정해진 간격으로 재시도하고, 그래도 실패하면 DLT 토픽으로 보낸다.
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponIssueConsumer {

	private final CouponIssueSaver couponIssueSaver;

	@KafkaListener(
			topics = "${coupon.kafka.topic}",
			groupId = "${coupon.kafka.group-id}",
			concurrency = "3",
			autoStartup = "${coupon.kafka.listener-auto-startup:true}")
	public void consume(CouponIssueMessage message) {
		couponIssueSaver.save(message);
	}

	/** 재시도까지 실패한 메시지. 지금은 기록만 남기고, 빠진 발급은 대사 작업이 보정한다. */
	@KafkaListener(
			topics = "${coupon.kafka.dead-letter-topic}",
			groupId = "coupon-issue-dlt-logger",
			autoStartup = "${coupon.kafka.listener-auto-startup:true}")
	public void consumeDeadLetter(CouponIssueMessage message) {
		log.error("발급 저장 최종 실패 (DLT): couponId={}, userId={}", message.couponId(), message.userId());
	}
}
