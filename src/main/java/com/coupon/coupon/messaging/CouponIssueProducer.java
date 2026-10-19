package com.coupon.coupon.messaging;

import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Component;

@Component
public class CouponIssueProducer {

	private static final long SEND_TIMEOUT_SECONDS = 3;

	private final KafkaTemplate<String, CouponIssueMessage> kafkaTemplate;
	private final String topic;

	public CouponIssueProducer(KafkaTemplate<String, CouponIssueMessage> kafkaTemplate,
			@Value("${coupon.kafka.topic}") String topic) {
		this.kafkaTemplate = kafkaTemplate;
		this.topic = topic;
	}

	/**
	 * 발급 메시지를 보내고, 브로커가 받았다고 답할 때까지 기다린다.
	 * 키를 유저 id로 두면 유저들이 파티션에 고르게 나뉘고, 같은 유저의 메시지는 같은 파티션으로 간다.
	 */
	public void send(CouponIssueMessage message) throws Exception {
		kafkaTemplate.send(topic, String.valueOf(message.userId()), message)
				.get(SEND_TIMEOUT_SECONDS, TimeUnit.SECONDS);
	}
}
