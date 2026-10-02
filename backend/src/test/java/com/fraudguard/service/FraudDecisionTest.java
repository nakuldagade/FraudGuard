package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// End to end: service -> outbox -> Kafka -> consumer -> Redis features -> rule engine -> database.
// Transactions for one account are read in order (same Kafka key), so the baseline transactions
// are always processed before the one that follows them.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class FraudDecisionTest {

    @Autowired TransactionService transactionService;
    @Autowired TransactionRepository repository;
    @Autowired MockMvc mvc;

    @Test
    void quietTransactionIsApprovedWithNoReasons() {
        UUID id = create(newAccount(), "100.00", "dev-1", "m-1", "IN");
        awaitStatus(id, TransactionStatus.APPROVED);

        Transaction t = repository.findById(id).orElseThrow();
        assertThat(t.getRiskScore()).isZero();
        assertThat(t.getRuleHits()).isEmpty();
    }

    @Test
    void suspiciousTransactionIsBlockedWithAllReasons() throws Exception {
        String account = newAccount();

        // Five ordinary transactions: the account's "normal" (about 100, same device, same country)
        for (String amount : new String[]{"98", "99", "100", "101", "102"}) {
            create(account, amount, "dev-1", "m-1", "IN");
        }
        // Then: a huge amount, from a new device, in another country, right after the others
        UUID outlier = create(account, "5000", "dev-9", "m-9", "US");

        awaitStatus(outlier, TransactionStatus.BLOCKED);

        Transaction t = repository.findById(outlier).orElseThrow();
        assertThat(t.getRiskScore()).isEqualTo(100);   // 50 + 40 + 35 + 30 = 155, capped at 100
        assertThat(t.getRuleHits()).extracting(RuleHit::code).containsExactly(
                "UNUSUAL_AMOUNT", "HIGH_VELOCITY", "RAPID_COUNTRY_CHANGE", "NEW_DEVICE_HIGH_AMOUNT");

        // The same verdict, with reasons, is visible through the API
        mvc.perform(get("/api/transactions/" + outlier))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.riskScore").value(100))
                .andExpect(jsonPath("$.ruleHits[0].code").value("UNUSUAL_AMOUNT"))
                .andExpect(jsonPath("$.ruleHits[0].reason").isNotEmpty());
    }

    @Test
    void burstOfTransactionsGoesToReview() {
        String account = newAccount();
        UUID last = null;
        for (int i = 0; i < 6; i++) {          // the 6th within 5 minutes exceeds the limit of 5
            last = create(account, "100.00", "dev-1", "m-1", "IN");
        }

        awaitStatus(last, TransactionStatus.REVIEW);

        Transaction t = repository.findById(last).orElseThrow();
        assertThat(t.getRiskScore()).isEqualTo(40);
        assertThat(t.getRuleHits()).extracting(RuleHit::code).containsExactly("HIGH_VELOCITY");
    }

    @Test
    void weakSignalIsApprovedButStillRecorded() {
        String account = newAccount();
        create(account, "100.00", "dev-1", "m-1", "IN");
        UUID second = create(account, "100.00", "dev-1", "m-1", "US");   // only the country changed

        awaitStatus(second, TransactionStatus.APPROVED);

        Transaction t = repository.findById(second).orElseThrow();
        assertThat(t.getRiskScore()).isEqualTo(35);   // below the review threshold of 40
        assertThat(t.getRuleHits()).extracting(RuleHit::code).containsExactly("RAPID_COUNTRY_CHANGE");
    }

    // ---------- helpers ----------

    private static String newAccount() {
        return "acc-" + UUID.randomUUID();
    }

    private UUID create(String account, String amount, String device, String merchant, String country) {
        var request = new TransactionRequestDTO(account, merchant, TransactionType.PAYMENT,
                new BigDecimal(amount), "INR", device, country, null);
        return transactionService.create(UUID.randomUUID().toString(), request).transaction().id();
    }

    // The consumer runs in the background, so wait (up to 30s) until the status reaches the expected one
    private void awaitStatus(UUID id, TransactionStatus expected) {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(expected));
    }
}