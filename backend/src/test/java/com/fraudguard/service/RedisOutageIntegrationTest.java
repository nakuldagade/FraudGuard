package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.TransactionRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.testcontainers.containers.GenericContainer;

// The recovery path end to end: Redis freezes -> the consumer retries and dead-letters -> the transaction
// stays PENDING -> Redis comes back -> the re-drive service decides it.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class RedisOutageIntegrationTest {

    @Autowired TransactionService transactionService;
    @Autowired TransactionRepository repository;
    @Autowired RedriveService redriveService;
    @Autowired MeterRegistry metrics;
    @Autowired @org.springframework.beans.factory.annotation.Qualifier("redisContainer") GenericContainer<?> redis;

    private double deadLettered() {
        return metrics.find("fraudguard.dlq.events").counters().stream().mapToDouble(Counter::count).sum();
    }

    @Test
    void redisOutageDeadLettersTheEventAndReDriveRecoversTheTransaction() {
        double before = deadLettered();
        var pause = redis.getDockerClient().pauseContainerCmd(redis.getContainerId());
        var unpause = redis.getDockerClient().unpauseContainerCmd(redis.getContainerId());
        UUID id;
        try {
            pause.exec();   // Redis stops answering: connections time out (2 s), they are not refused

            id = transactionService.create("outage-" + UUID.randomUUID(), new TransactionRequestDTO(
                    "acc-outage-" + UUID.randomUUID(), "m-1", TransactionType.PAYMENT,
                    new BigDecimal("20.00"), "INR", "dev-1", "IN", null)).transaction().id();

            // 4 attempts (the first plus 3 retries after 1, 2, 4 s), each failing after the Redis timeout
            await().atMost(Duration.ofSeconds(120)).untilAsserted(() ->
                    assertThat(deadLettered()).isGreaterThan(before));
            assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(TransactionStatus.PENDING);
        } finally {
            unpause.exec();
        }

        assertThat(redriveService.redrivePending(Duration.ZERO, 500).requeued()).isGreaterThanOrEqualTo(1);
        await().atMost(Duration.ofSeconds(60)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(TransactionStatus.APPROVED));
    }
}
