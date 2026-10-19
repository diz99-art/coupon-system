package com.coupon.coupon;

import java.util.Collection;
import java.util.Map;
import org.apache.kafka.common.TopicPartition;
import org.springframework.kafka.config.KafkaListenerEndpointRegistry;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;
import org.springframework.kafka.listener.MessageListenerContainer;

public final class KafkaTestSupport {

	private static final long TIMEOUT_MILLIS = 30_000;

	private KafkaTestSupport() {
	}

	/**
	 * 리스너 컨테이너마다 모든 컨슈머가 파티션을 하나 이상 받을 때까지 기다린다.
	 * 컨슈머가 늦게 그룹에 들어오면 처리 도중에 파티션 재배정이 일어나고, 처리 중이던 메시지가 다시 전달된다.
	 */
	public static void waitForAssignment(KafkaListenerEndpointRegistry registry) {
		for (MessageListenerContainer container : registry.getListenerContainers()) {
			int consumers = container instanceof ConcurrentMessageListenerContainer<?, ?> concurrent
					? concurrent.getConcurrency() : 1;
			long deadline = System.currentTimeMillis() + TIMEOUT_MILLIS;
			while (assignedConsumers(container) < consumers) {
				if (System.currentTimeMillis() > deadline) {
					throw new IllegalStateException("파티션 배정을 기다리다 시간 초과: " + container.getListenerId());
				}
				sleep();
			}
		}
	}

	private static long assignedConsumers(MessageListenerContainer container) {
		Map<String, Collection<TopicPartition>> assignments = container.getAssignmentsByClientId();
		if (assignments == null) {
			return 0;
		}
		return assignments.values().stream().filter(partitions -> partitions != null && !partitions.isEmpty()).count();
	}

	private static void sleep() {
		try {
			Thread.sleep(50);
		} catch (InterruptedException e) {
			Thread.currentThread().interrupt();
			throw new IllegalStateException(e);
		}
	}
}
