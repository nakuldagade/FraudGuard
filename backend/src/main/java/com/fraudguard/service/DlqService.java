package com.fraudguard.service;

import com.fraudguard.config.KafkaConfig;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.springframework.boot.autoconfigure.kafka.KafkaConnectionDetails;
import org.springframework.boot.autoconfigure.kafka.KafkaProperties;
import org.springframework.stereotype.Service;

// Read-only look at the dead-letter topic: how many records it holds and why the recent ones failed.
// It uses a throwaway consumer that assigns partitions directly and never commits offsets,
// so it cannot disturb the real consumer group.
@Service
@RequiredArgsConstructor
public class DlqService {

    public record DlqRecord(int partition, long offset, Instant timestamp, String key, String exception) {
    }

    public record Summary(long totalMessages, Map<String, Long> recentByException, List<DlqRecord> recent) {
    }

    private static final int RECENT_PER_PARTITION = 10;
    // Set by Spring's DLQ recoverer. The listener wraps every failure, so the "cause" header holds the real
    // exception (e.g. IllegalArgumentException); the plain header is the wrapper and is only a fallback.
    private static final String CAUSE_HEADER = "kafka_dlt-exception-cause-fqcn";
    private static final String EXCEPTION_HEADER = "kafka_dlt-exception-fqcn";

    private final KafkaProperties kafkaProperties;
    private final KafkaConnectionDetails connectionDetails;   // the broker address actually in use

    public Summary summarize() {
        Map<String, Object> props = kafkaProperties.buildConsumerProperties(null);
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.ENABLE_AUTO_COMMIT_CONFIG, false);
        props.remove(ConsumerConfig.GROUP_ID_CONFIG);
        props.put(ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, connectionDetails.getConsumerBootstrapServers());
        props.put(ConsumerConfig.DEFAULT_API_TIMEOUT_MS_CONFIG, 5_000);   // fail fast if Kafka is down

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            List<TopicPartition> partitions = consumer.partitionsFor(KafkaConfig.TOPIC_DLQ).stream()
                    .map(p -> new TopicPartition(p.topic(), p.partition())).toList();
            consumer.assign(partitions);
            Map<TopicPartition, Long> begin = consumer.beginningOffsets(partitions);
            Map<TopicPartition, Long> end = consumer.endOffsets(partitions);

            long total = 0;
            for (TopicPartition tp : partitions) {
                total += end.get(tp) - begin.get(tp);
                // Start a few records before the end, so we only read the most recent ones
                consumer.seek(tp, Math.max(begin.get(tp), end.get(tp) - RECENT_PER_PARTITION));
            }

            List<DlqRecord> recent = new ArrayList<>();
            long deadline = System.currentTimeMillis() + 3_000;
            while (System.currentTimeMillis() < deadline && !reachedEnd(consumer, partitions, end)) {
                for (ConsumerRecord<String, String> r : consumer.poll(Duration.ofMillis(300))) {
                    recent.add(new DlqRecord(r.partition(), r.offset(), Instant.ofEpochMilli(r.timestamp()),
                            r.key(), exceptionOf(r)));
                }
            }
            recent.sort(Comparator.comparing(DlqRecord::timestamp).reversed());
            Map<String, Long> byException = recent.stream().collect(
                    Collectors.groupingBy(DlqRecord::exception, Collectors.counting()));
            return new Summary(total, byException, recent);
        }
    }

    private static boolean reachedEnd(KafkaConsumer<?, ?> consumer, List<TopicPartition> partitions,
                                      Map<TopicPartition, Long> end) {
        return partitions.stream().allMatch(tp -> consumer.position(tp) >= end.get(tp));
    }

    private static String exceptionOf(ConsumerRecord<?, ?> r) {
        Header header = r.headers().lastHeader(CAUSE_HEADER);
        if (header == null) {
            header = r.headers().lastHeader(EXCEPTION_HEADER);
        }
        return header == null ? "unknown" : new String(header.value(), StandardCharsets.UTF_8);
    }
}
