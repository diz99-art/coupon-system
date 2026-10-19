package com.coupon.coupon.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doCallRealMethod;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.timeout;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import com.coupon.coupon.KafkaTestSupport;
import com.coupon.coupon.RedisTestSupport;
import com.coupon.coupon.domain.Coupon;
import com.coupon.coupon.messaging.CouponIssueMessage;
import com.coupon.coupon.messaging.CouponIssueProducer;
import com.coupon.coupon.repository.CouponIssueRedisRepository;
import com.coupon.coupon.repository.CouponIssueRepository;
import com.coupon.coupon.repository.CouponRepository;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.test.EmbeddedKafkaBroker;
import org.springframework.kafka.test.context.EmbeddedKafka;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/**
 * DB 저장을 일부러 실패시켜 재시도, DLT, 멱등 저장, 대사 보정을 확인한다.
 * 테스트마다 깨끗한 Kafka를 쓰도록 내장 Kafka(EmbeddedKafka)를 띄운다.
 */
@SpringBootTest(properties = {
		"spring.kafka.bootstrap-servers=${spring.embedded.kafka.brokers}",
		"coupon.kafka.listener-auto-startup=true",
		"coupon.kafka.retry.interval-ms=300",
		"coupon.kafka.retry.max-retries=3"})
// 토픽은 KafkaConfig의 NewTopic 빈이 만든다 (파티션 수를 운영과 같게).
@EmbeddedKafka
@DirtiesContext
class KafkaFailureHandlingTest {

	private static final int MAX_RETRIES = 3;

	@MockitoSpyBean
	private CouponIssueSaver couponIssueSaver;

	@Autowired
	private CouponIssueProducer couponIssueProducer;

	@Autowired
	private KafkaCouponIssueService kafkaCouponIssueService;

	@Autowired
	private CouponReconciliationService couponReconciliationService;

	@Autowired
	private CouponRepository couponRepository;

	@Autowired
	private CouponIssueRepository couponIssueRepository;

	@Autowired
	private CouponIssueRedisRepository couponIssueRedisRepository;

	@Autowired
	private StringRedisTemplate redisTemplate;

	@Autowired
	private EmbeddedKafkaBroker embeddedKafka;

	@Autowired
	private KafkaListenerEndpointRegistry listenerRegistry;

	@Value("${coupon.kafka.dead-letter-topic}")
	private String deadLetterTopic;

	private Coupon coupon;

	@BeforeEach
	void setUp() {
		// 컨슈머들이 파티션을 다 나눠 가진 뒤에 시작한다. 도중에 재배정이 일어나면 메시지가 다시 전달되어 시도 횟수가 늘어난다.
		KafkaTestSupport.waitForAssignment(listenerRegistry);
		couponIssueRepository.deleteAllInBatch();
		couponRepository.deleteAllInBatch();
		RedisTestSupport.clearCouponKeys(redisTemplate);
		LocalDateTime now = LocalDateTime.now();
		coupon = couponRepository.save(new Coupon("Kafka 쿠폰", 100, now.minusHours(1), now.plusHours(1)));
	}

	@Test
	@DisplayName("DB 저장이 두 번 실패해도 재시도로 세 번째에 저장된다")
	void retryThenSucceed() {
		DataAccessResourceFailureException dbDown = new DataAccessResourceFailureException("DB 장애 흉내");
		doThrow(dbDown).doThrow(dbDown).doCallRealMethod().when(couponIssueSaver).save(any());

		kafkaCouponIssueService.issue(coupon.getId(), 1L);

		assertThat(waitUntilSaved(1L)).isTrue();
		verify(couponIssueSaver, times(3)).save(argThat(message -> message.userId() == 1L));
		System.out.println("[실패 처리] 저장 시도 3번 (실패 2번 + 성공 1번) 뒤 DB에 저장됨");
	}

	@Test
	@DisplayName("재시도까지 모두 실패하면 DLT로 가고, 대사 작업이 DB를 보정한다")
	void exhaustedRetriesGoToDeadLetterTopic() {
		long userId = 999L;
		doThrow(new DataAccessResourceFailureException("DB 장애 흉내"))
				.when(couponIssueSaver).save(argThat(message -> message != null && message.userId() == userId));

		long startedAt = System.currentTimeMillis();
		kafkaCouponIssueService.issue(coupon.getId(), userId);

		ConsumerRecord<String, String> deadLetter = pollDeadLetter(userId);
		long elapsed = System.currentTimeMillis() - startedAt;
		assertThat(deadLetter).isNotNull();
		verify(couponIssueSaver, times(1 + MAX_RETRIES)).save(argThat(message -> message.userId() == userId));
		assertThat(couponIssueRepository.existsByCouponIdAndUserId(coupon.getId(), userId)).isFalse();
		assertThat(couponIssueRedisRepository.issuedUserIds(coupon.getId())).contains(userId);
		System.out.printf("[실패 처리] 저장 시도 %d번 모두 실패 → %dms 뒤 DLT 도착: %s%n",
				1 + MAX_RETRIES, elapsed, deadLetter.value());
		System.out.println("[실패 처리] 이 시점: Redis에는 발급됨, DB에는 없음 (불일치)");

		// DB가 복구됐다고 보고 대사 작업을 돌린다.
		reset(couponIssueSaver);
		CouponReconciliationService.Result result = couponReconciliationService.reconcile();
		assertThat(result.savedToDb()).isEqualTo(1);
		assertThat(couponIssueRepository.existsByCouponIdAndUserId(coupon.getId(), userId)).isTrue();
		System.out.println("[실패 처리] 대사 작업 후 DB에 저장됨: " + result);
	}

	@Test
	@DisplayName("같은 메시지가 두 번 와도 DB에는 한 건만 저장된다")
	void duplicateMessageIsSavedOnce() throws Exception {
		CouponIssueMessage message = new CouponIssueMessage(coupon.getId(), 7L, LocalDateTime.now());

		couponIssueProducer.send(message);
		couponIssueProducer.send(message);

		verify(couponIssueSaver, timeout(10_000).times(2)).save(argThat(m -> m.userId() == 7L));
		// 두 번째 처리가 끝나 커밋까지 되도록 잠깐 기다린 뒤 센다.
		Thread.sleep(500);
		assertThat(couponIssueRepository.countByCouponId(coupon.getId())).isEqualTo(1);
		System.out.println("[실패 처리] 같은 메시지 2번 처리 → DB 1건");
	}

	private boolean waitUntilSaved(long userId) {
		long deadline = System.currentTimeMillis() + 10_000;
		while (System.currentTimeMillis() < deadline) {
			if (couponIssueRepository.existsByCouponIdAndUserId(coupon.getId(), userId)) {
				return true;
			}
			try {
				Thread.sleep(50);
			} catch (InterruptedException e) {
				Thread.currentThread().interrupt();
				return false;
			}
		}
		return false;
	}

	private ConsumerRecord<String, String> pollDeadLetter(long userId) {
		Map<String, Object> props = Map.of(
				ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, embeddedKafka.getBrokersAsString(),
				ConsumerConfig.GROUP_ID_CONFIG, "dlt-check-" + userId,
				ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
				ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
				ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
		try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
			consumer.subscribe(List.of(deadLetterTopic));
			long deadline = System.currentTimeMillis() + 20_000;
			while (System.currentTimeMillis() < deadline) {
				for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(200))) {
					if (record.value().contains("\"userId\":" + userId)) {
						return record;
					}
				}
			}
		}
		return null;
	}
}
