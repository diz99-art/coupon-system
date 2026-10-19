package com.coupon.coupon.config;

import com.coupon.coupon.repository.CouponIssueRedisRepository;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import lombok.extern.slf4j.Slf4j;
import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult.ListOffsetsResultInfo;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.RecordsToDelete;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.common.TopicPartition;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.stereotype.Component;

/**
 * 개발 환경(local)에서는 서버를 켤 때마다 DB 테이블을 새로 만든다(ddl-auto: create). 그래서 다른 저장소도 함께 비운다.
 * - Redis 쿠폰 키: 남겨 두면 대사 작업이 지난번 발급을 새 DB에 채워 넣는다.
 * - Kafka 발급 토픽의 남은 메시지: 지난번에 처리되지 않은 발급 메시지를 컨슈머가 새 DB에 저장한다.
 * 빈이 만들어지는 시점에 실행되므로 Kafka 리스너와 스케줄러가 시작되기 전에 끝난다.
 */
@Slf4j
@Component
@Profile("local")
public class LocalDevDataInitializer implements InitializingBean {

	private static final long KAFKA_TIMEOUT_SECONDS = 10;

	private final CouponIssueRedisRepository couponIssueRedisRepository;
	private final KafkaAdmin kafkaAdmin;
	private final List<String> topics;

	public LocalDevDataInitializer(CouponIssueRedisRepository couponIssueRedisRepository, KafkaAdmin kafkaAdmin,
			@Value("${coupon.kafka.topic}") String topic,
			@Value("${coupon.kafka.dead-letter-topic}") String deadLetterTopic) {
		this.couponIssueRedisRepository = couponIssueRedisRepository;
		this.kafkaAdmin = kafkaAdmin;
		this.topics = List.of(topic, deadLetterTopic);
	}

	@Override
	public void afterPropertiesSet() throws Exception {
		long deletedKeys = couponIssueRedisRepository.deleteAll();
		long deletedRecords = deleteKafkaRecords();
		log.info("local 프로필: Redis 쿠폰 키 {}개, Kafka 남은 메시지 {}개를 지웠다", deletedKeys, deletedRecords);
	}

	/** 토픽의 각 파티션에서 지금 끝 위치 앞의 메시지를 모두 지운다. 토픽이 아직 없으면 지울 것도 없다. */
	private long deleteKafkaRecords() throws Exception {
		try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
			Set<String> existing = admin.listTopics().names().get(KAFKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			List<String> targets = topics.stream().filter(existing::contains).toList();
			if (targets.isEmpty()) {
				return 0;
			}
			Map<String, TopicDescription> descriptions = admin.describeTopics(targets).allTopicNames()
					.get(KAFKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			Map<TopicPartition, OffsetSpec> partitions = descriptions.values().stream()
					.flatMap(d -> d.partitions().stream().map(p -> new TopicPartition(d.name(), p.partition())))
					.collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.latest()));
			Map<TopicPartition, ListOffsetsResultInfo> ends = admin.listOffsets(partitions).all()
					.get(KAFKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			Map<TopicPartition, OffsetSpec> starts = partitions.keySet().stream()
					.collect(Collectors.toMap(tp -> tp, tp -> OffsetSpec.earliest()));
			Map<TopicPartition, ListOffsetsResultInfo> begins = admin.listOffsets(starts).all()
					.get(KAFKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);

			long remaining = ends.entrySet().stream()
					.mapToLong(e -> e.getValue().offset() - begins.get(e.getKey()).offset())
					.sum();
			if (remaining > 0) {
				Map<TopicPartition, RecordsToDelete> toDelete = ends.entrySet().stream()
						.collect(Collectors.toMap(Map.Entry::getKey,
								e -> RecordsToDelete.beforeOffset(e.getValue().offset())));
				admin.deleteRecords(toDelete).all().get(KAFKA_TIMEOUT_SECONDS, TimeUnit.SECONDS);
			}
			return remaining;
		}
	}
}
