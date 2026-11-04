package com.fraudguard.controller;

import static com.fraudguard.TestAuth.get;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// The hourly activity chart's data: continuous hours, zeros included, counted by decision.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TransactionTrendApiTest {

    @Autowired MockMvc mvc;
    @Autowired TransactionRepository repository;
    @Autowired ObjectMapper objectMapper;

    private void save(TransactionStatus status) {
        Transaction t = new Transaction();
        t.setIdempotencyKey("trend-" + UUID.randomUUID());
        t.setRequestHash("h".repeat(64));
        t.setAccountId("acc-trend");
        t.setTransactionType(TransactionType.DEPOSIT);
        t.setAmount(new BigDecimal("1.00"));
        t.setCurrency("USD");
        t.setOccurredAt(Instant.now());
        t.setStatus(status);
        repository.saveAndFlush(t);
    }

    private JsonNode trend(String query) throws Exception {
        String body = mvc.perform(get("/api/transactions/trend" + query)).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body);
    }

    @Test
    void returnsOneContinuousPointPerHourWithTheLatestHourLast() throws Exception {
        JsonNode points = trend("?hours=6");

        assertThat(points).hasSize(6);
        Instant previous = null;
        for (JsonNode p : points) {
            Instant hour = Instant.parse(p.get("hour").asText());
            if (previous != null) {
                assertThat(hour).isEqualTo(previous.plusSeconds(3600));   // no gaps
            }
            previous = hour;
        }
    }

    @Test
    void countsTransactionsByDecisionInTheCurrentHour() throws Exception {
        JsonNode before = trend("?hours=2").get(1);
        save(TransactionStatus.APPROVED);
        save(TransactionStatus.APPROVED);
        save(TransactionStatus.REVIEW);
        save(TransactionStatus.BLOCKED);
        JsonNode after = trend("?hours=2").get(1);

        assertThat(after.get("approved").asLong() - before.get("approved").asLong()).isEqualTo(2);
        assertThat(after.get("review").asLong() - before.get("review").asLong()).isEqualTo(1);
        assertThat(after.get("blocked").asLong() - before.get("blocked").asLong()).isEqualTo(1);
    }

    @Test
    void hoursAreClampedBetweenOneAndAWeek() throws Exception {
        assertThat(trend("?hours=0")).hasSize(1);
        assertThat(trend("?hours=100000")).hasSize(168);
        mvc.perform(get("/api/transactions/trend")).andExpect(jsonPath("$.length()").value(24));
    }
}
