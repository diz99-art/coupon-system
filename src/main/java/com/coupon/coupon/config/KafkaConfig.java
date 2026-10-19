package com.coupon.coupon.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.FixedBackOff;

@Configuration
public class KafkaConfig {

	/** 발급 메시지 토픽. 파티션 3개라 컨슈머 3개가 나눠서 처리할 수 있다. */
	@Bean
	public NewTopic couponIssueTopic(@Value("${coupon.kafka.topic}") String topic) {
		return TopicBuilder.name(topic).partitions(3).replicas(1).build();
	}

	/** 재시도까지 실패한 발급 메시지가 모이는 곳 (Dead Letter Topic) */
	@Bean
	public NewTopic couponIssueDeadLetterTopic(@Value("${coupon.kafka.dead-letter-topic}") String deadLetterTopic) {
		return TopicBuilder.name(deadLetterTopic).partitions(1).replicas(1).build();
	}

	/**
	 * 컨슈머에서 예외가 나면 intervalMs 간격으로 maxRetries번 다시 시도한다.
	 * 그래도 실패하면 메시지를 DLT 토픽으로 보내고 다음 메시지로 넘어간다.
	 * 스프링 부트가 이 빈을 기본 리스너 컨테이너에 자동으로 붙인다.
	 */
	@Bean
	public DefaultErrorHandler kafkaErrorHandler(
			KafkaTemplate<Object, Object> kafkaTemplate,
			@Value("${coupon.kafka.retry.interval-ms}") long intervalMs,
			@Value("${coupon.kafka.retry.max-retries}") long maxRetries,
			@Value("${coupon.kafka.dead-letter-topic}") String deadLetterTopic) {
		// 파티션을 -1로 두면 DLT 안에서 파티션을 Kafka가 고른다.
		DeadLetterPublishingRecoverer recoverer = new DeadLetterPublishingRecoverer(kafkaTemplate,
				(record, exception) -> new TopicPartition(deadLetterTopic, -1));
		return new DefaultErrorHandler(recoverer, new FixedBackOff(intervalMs, maxRetries));
	}
}
