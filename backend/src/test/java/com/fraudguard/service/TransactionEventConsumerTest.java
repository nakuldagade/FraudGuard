package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.config.KafkaConfig;
import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.kafka.KafkaContainer;

@SpringBootTest
@AutoConfigureMockMvc   // not used here; it just makes this test's setup identical to
@ActiveProfiles("test") // TransactionControllerTest, so both share ONE app + containers
@Import(TestcontainersConfig.class)
class TransactionEventConsumerTest {

    @Autowired TransactionService transactionService;
    @Autowired TransactionRepository repository;
    @Autowired KafkaTemplate<String, String> kafkaTemplate;
    @Autowired ObjectMapper objectMapper;
    @Autowired KafkaContainer kafka;   // the test broker, so we can read the dead-letter topic

    @Test
    void createdTransactionIsProcessedAsynchronously() {
        UUID id = createTransaction("acc-flow-" + UUID.randomUUID()).getId();

        // The consumer runs in the background, so wait (up to 30s) for the status to change
        awaitStatus(id, TransactionStatus.APPROVED);
        assertThat(repository.findById(id).orElseThrow().getProcessedEventId()).isNotNull();
    }

    @Test
    void duplicateEventIsSkipped() throws Exception {
        // Same account => same Kafka key => same partition => messages are read strictly in order
        String account = "acc-dup-" + UUID.randomUUID();

        Transaction a = createTransaction(account);
        awaitStatus(a.getId(), TransactionStatus.APPROVED);
        a = repository.findById(a.getId()).orElseThrow();
        UUID eventIdOfA = a.getProcessedEventId();

        // Put A back to PENDING, then re-deliver the SAME event (same eventId)
        a.setStatus(TransactionStatus.PENDING);
        repository.save(a);
        TransactionEvent duplicate = new TransactionEvent(
                eventIdOfA, TransactionEvent.TYPE_CREATED, TransactionEvent.SCHEMA_VERSION, Instant.now(),
                new TransactionEvent.Payload(a.getId(), account, a.getMerchantId(), a.getTransactionType(),
                        a.getAmount(), a.getCurrency(), a.getDeviceId(), a.getCountry(), a.getOccurredAt()));
        kafkaTemplate.send(KafkaConfig.TOPIC_RAW, account, objectMapper.writeValueAsString(duplicate)).get();

        // Create B for the same account. Its event queues up AFTER the duplicate, so once B is
        // processed we know the duplicate has already been handled.
        Transaction b = createTransaction(account);
        awaitStatus(b.getId(), TransactionStatus.APPROVED);

        // The duplicate was recognised and skipped, so A was not approved a second time
        assertThat(repository.findById(a.getId()).orElseThrow().getStatus())
                .isEqualTo(TransactionStatus.PENDING);
    }

    @Test
    void malformedMessageGoesStraightToDeadLetterTopic() throws Exception {
        String garbage = "not-json-" + UUID.randomUUID();
        kafkaTemplate.send(KafkaConfig.TOPIC_RAW, "acc-bad", garbage).get();

        assertThat(awaitDeadLetter(garbage)).isEqualTo(garbage);
    }

    @Test
    void eventForUnknownTransactionIsRetriedThenDeadLettered() throws Exception {
        UUID unknownId = UUID.randomUUID();
        TransactionEvent event = new TransactionEvent(
                UUID.randomUUID(), TransactionEvent.TYPE_CREATED, TransactionEvent.SCHEMA_VERSION, Instant.now(),
                new TransactionEvent.Payload(unknownId, "acc-unknown", "m-1", TransactionType.PAYMENT,
                        new BigDecimal("10.00"), "INR", null, null, Instant.now()));
        kafkaTemplate.send(KafkaConfig.TOPIC_RAW, "acc-unknown", objectMapper.writeValueAsString(event)).get();

        // Retries after 1s, 2s and 4s (about 7s in total), then the message lands in the DLQ
        assertThat(awaitDeadLetter(unknownId.toString())).contains(unknownId.toString());
    }

    // ---------- helpers ----------

    // Saves a transaction through the real service (which also publishes the Kafka event)
    private Transaction createTransaction(String accountId) {
        var request = new TransactionRequestDTO(accountId, "m-1", TransactionType.PAYMENT,
                new BigDecimal("100.00"), "INR", "dev-1", "IN", null);
        UUID id = transactionService.create(UUID.randomUUID().toString(), request).transaction().id();
        return repository.findById(id).orElseThrow();
    }

    // Polls the database until the transaction reaches the expected status
    private void awaitStatus(UUID id, TransactionStatus expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(expected));
    }

    // Reads the dead-letter topic until a message containing `marker` shows up
    private String awaitDeadLetter(String marker) {
        Map<String, Object> props = Map.of(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG, kafka.getBootstrapServers(),
                ConsumerConfig.GROUP_ID_CONFIG, "dlq-test-" + UUID.randomUUID(),
                ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest",
                ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class,
                ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(KafkaConfig.TOPIC_DLQ));
            long deadline = System.currentTimeMillis() + 40_000;
            while (System.currentTimeMillis() < deadline) {
                for (ConsumerRecord<String, String> record : consumer.poll(Duration.ofMillis(500))) {
                    if (record.value() != null && record.value().contains(marker)) {
                        return record.value();
                    }
                }
            }
        }
        throw new AssertionError("No dead-letter message containing: " + marker);
    }
}