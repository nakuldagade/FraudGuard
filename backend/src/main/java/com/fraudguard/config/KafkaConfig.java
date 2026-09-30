package com.fraudguard.config;

import org.apache.kafka.clients.admin.NewTopic;
import org.apache.kafka.common.TopicPartition;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.config.TopicBuilder;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.kafka.support.ExponentialBackOffWithMaxRetries;

@Configuration
public class KafkaConfig {

    // Topic names in one place, so the rest of the code never repeats string literals
    public static final String TOPIC_RAW = "transactions.raw";              // new transactions
    public static final String TOPIC_SCORED = "transactions.scored";        // used from Phase 6
    public static final String TOPIC_DECISIONS = "transactions.decisions";  // used from Phase 7
    public static final String TOPIC_DLQ = "transactions.dlq";              // events that failed processing

    // Spring's KafkaAdmin creates any of these that don't exist yet when the app starts.
    // 3 partitions lets up to 3 consumers work in parallel; 1 replica because local Kafka has one broker.
    @Bean
    NewTopic rawTopic() {
        return TopicBuilder.name(TOPIC_RAW).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic scoredTopic() {
        return TopicBuilder.name(TOPIC_SCORED).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic decisionsTopic() {
        return TopicBuilder.name(TOPIC_DECISIONS).partitions(3).replicas(1).build();
    }

    @Bean
    NewTopic dlqTopic() {
        return TopicBuilder.name(TOPIC_DLQ).partitions(3).replicas(1).build();
    }

    // Error handling for every @KafkaListener (Spring Boot picks this bean up automatically).
    // A failed message is retried 3 times, waiting 1s, 2s, 4s in between. If it still fails,
    // it is copied to the dead-letter topic so it doesn't block the messages behind it.
    @Bean
    DefaultErrorHandler kafkaErrorHandler(KafkaTemplate<String, String> kafkaTemplate) {
        // -1 = let Kafka choose the partition in the DLQ topic
        DeadLetterPublishingRecoverer toDlq = new DeadLetterPublishingRecoverer(
                kafkaTemplate, (record, exception) -> new TopicPartition(TOPIC_DLQ, -1));

        ExponentialBackOffWithMaxRetries backOff = new ExponentialBackOffWithMaxRetries(3);
        backOff.setInitialInterval(1_000L);   // first wait: 1 second
        backOff.setMultiplier(2.0);           // then double it each time
        backOff.setMaxInterval(10_000L);      // never wait more than 10 seconds

        DefaultErrorHandler handler = new DefaultErrorHandler(toDlq, backOff);
        // A malformed message will never succeed, so don't waste retries on it: straight to the DLQ
        handler.addNotRetryableExceptions(IllegalArgumentException.class);
        return handler;
    }
}