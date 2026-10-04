package com.fraudguard.controller;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.options;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.TransactionRepository;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

// Transactions are saved straight into the database with chosen values (no Kafka involved),
// so each test controls exactly what the list, the filters and the review actions see.
// Every test uses its own account, so tests can't see each other's data.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class TransactionReviewApiTest {

    @Autowired MockMvc mvc;
    @Autowired TransactionRepository repository;

    private static final Instant T0 = Instant.parse("2026-01-01T12:00:00Z");

    // ---------- list, paging, filters ----------

    @Test
    void listIsPagedAndNewestFirst() throws Exception {
        String account = newAccount();
        for (int i = 0; i < 5; i++) {
            save(account, TransactionStatus.APPROVED, 10, T0.plus(Duration.ofMinutes(i)));
        }

        mvc.perform(get("/api/transactions").param("accountId", account).param("size", "2").param("page", "0"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].occurredAt").value("2026-01-01T12:04:00Z"))   // newest first
                .andExpect(jsonPath("$.content[1].occurredAt").value("2026-01-01T12:03:00Z"))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.totalPages").value(3));

        mvc.perform(get("/api/transactions").param("accountId", account).param("size", "2").param("page", "2"))
                .andExpect(jsonPath("$.content.length()").value(1));   // the last, partly filled page
    }

    @Test
    void filterByDecisionStatus() throws Exception {
        String account = newAccount();
        save(account, TransactionStatus.APPROVED, 0, T0);
        save(account, TransactionStatus.REVIEW, 45, T0.plusSeconds(1));
        save(account, TransactionStatus.BLOCKED, 90, T0.plusSeconds(2));

        mvc.perform(get("/api/transactions").param("accountId", account).param("status", "REVIEW", "BLOCKED"))
                .andExpect(jsonPath("$.totalElements").value(2))
                .andExpect(jsonPath("$.content[0].status").value("BLOCKED"))
                .andExpect(jsonPath("$.content[1].status").value("REVIEW"));
    }

    @Test
    void filterByRiskScoreRange() throws Exception {
        String account = newAccount();
        save(account, TransactionStatus.APPROVED, 10, T0);
        save(account, TransactionStatus.REVIEW, 45, T0.plusSeconds(1));
        save(account, TransactionStatus.BLOCKED, 90, T0.plusSeconds(2));

        // "Medium" risk = rule score 40 to 79
        mvc.perform(get("/api/transactions").param("accountId", account)
                        .param("minRiskScore", "40").param("maxRiskScore", "79"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].riskScore").value(45));
    }

    @Test
    void filterByDateRangeStartsInclusiveAndEndsExclusive() throws Exception {
        String account = newAccount();
        save(account, TransactionStatus.APPROVED, 0, Instant.parse("2026-01-01T12:00:00Z"));
        save(account, TransactionStatus.APPROVED, 0, Instant.parse("2026-01-02T12:00:00Z"));
        save(account, TransactionStatus.APPROVED, 0, Instant.parse("2026-01-03T12:00:00Z"));

        mvc.perform(get("/api/transactions").param("accountId", account)
                        .param("from", "2026-01-02T00:00:00Z").param("to", "2026-01-03T00:00:00Z"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].occurredAt").value("2026-01-02T12:00:00Z"));
    }

    @Test
    void filterByReviewStateAndAccount() throws Exception {
        String account = newAccount();
        Transaction reviewed = save(account, TransactionStatus.REVIEW, 45, T0);
        save(account, TransactionStatus.REVIEW, 50, T0.plusSeconds(1));
        review(reviewed.getId(), "CONFIRM_FRAUD", "Confirmed with the customer", "nakul");

        mvc.perform(get("/api/transactions").param("accountId", account).param("reviewState", "UNREVIEWED"))
                .andExpect(jsonPath("$.totalElements").value(1));
        mvc.perform(get("/api/transactions").param("accountId", account).param("reviewState", "CONFIRMED_FRAUD"))
                .andExpect(jsonPath("$.totalElements").value(1))
                .andExpect(jsonPath("$.content[0].id").value(reviewed.getId().toString()));
    }

    // ---------- analyst actions ----------

    @Test
    void reviewIsRecordedSeparatelyFromTheModelDecision() throws Exception {
        Transaction t = save(newAccount(), TransactionStatus.REVIEW, 45, T0);

        mvc.perform(post("/api/transactions/" + t.getId() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM_FRAUD\",\"note\":\"Card was reported stolen\",\"reviewer\":\"nakul\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.action").value("CONFIRM_FRAUD"))
                .andExpect(jsonPath("$.reviewer").value("nakul"))
                .andExpect(jsonPath("$.createdAt").exists());

        mvc.perform(get("/api/transactions/" + t.getId() + "/detail"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.transaction.status").value("REVIEW"))                    // the model's decision is untouched
                .andExpect(jsonPath("$.transaction.reviewState").value("CONFIRMED_FRAUD"))     // the analyst's verdict
                .andExpect(jsonPath("$.reviews.length()").value(1))
                .andExpect(jsonPath("$.reviews[0].note").value("Card was reported stolen"));
    }

    @Test
    void latestActionDefinesTheStateAndTheHistoryKeepsEverything() throws Exception {
        Transaction t = save(newAccount(), TransactionStatus.REVIEW, 45, T0);

        review(t.getId(), "REQUEST_FURTHER_REVIEW", "Need the merchant's invoice", "nakul");
        Thread.sleep(5);   // make sure the two actions get different timestamps
        review(t.getId(), "MARK_LEGITIMATE", "Invoice received, genuine purchase", "priya");

        mvc.perform(get("/api/transactions/" + t.getId() + "/detail"))
                .andExpect(jsonPath("$.transaction.reviewState").value("MARKED_LEGITIMATE"))
                .andExpect(jsonPath("$.reviews.length()").value(2))
                .andExpect(jsonPath("$.reviews[0].action").value("MARK_LEGITIMATE"))            // newest first
                .andExpect(jsonPath("$.reviews[0].reviewer").value("priya"))
                .andExpect(jsonPath("$.reviews[1].action").value("REQUEST_FURTHER_REVIEW"));
    }

    @Test
    void detailIncludesTheStoredDecisionInputsAsJson() throws Exception {
        Transaction t = save(newAccount(), TransactionStatus.REVIEW, 45, T0);
        t.setDecisionInputs("{\"policyVersion\":\"policy-v1\",\"ruleScore\":45}");
        repository.saveAndFlush(t);

        mvc.perform(get("/api/transactions/" + t.getId() + "/detail"))
                .andExpect(jsonPath("$.decisionInputs.policyVersion").value("policy-v1"))
                .andExpect(jsonPath("$.decisionInputs.ruleScore").value(45));
    }

    @Test
    void reviewWithoutReviewerIsRejected() throws Exception {
        Transaction t = save(newAccount(), TransactionStatus.REVIEW, 45, T0);

        mvc.perform(post("/api/transactions/" + t.getId() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM_FRAUD\",\"note\":\"x\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors.reviewer").exists());
    }

    @Test
    void reviewWithUnknownActionIsRejected() throws Exception {
        Transaction t = save(newAccount(), TransactionStatus.REVIEW, 45, T0);

        mvc.perform(post("/api/transactions/" + t.getId() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"DELETE_EVERYTHING\",\"reviewer\":\"nakul\"}"))
                .andExpect(status().isBadRequest());
    }

    @Test
    void reviewOfUnknownTransactionIsNotFound() throws Exception {
        mvc.perform(post("/api/transactions/" + UUID.randomUUID() + "/review")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"CONFIRM_FRAUD\",\"reviewer\":\"nakul\"}"))
                .andExpect(status().isNotFound());
    }

    // ---------- summary and CORS ----------

    @Test
    void summaryCountsWhatIsWaitingForAnAnalyst() throws Exception {
        save(newAccount(), TransactionStatus.BLOCKED, 90, T0);   // blocked and nobody has looked at it yet

        mvc.perform(get("/api/transactions/summary"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.awaitingReview").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byStatus.BLOCKED").value(greaterThanOrEqualTo(1)))
                .andExpect(jsonPath("$.byReviewState.UNREVIEWED").value(greaterThanOrEqualTo(1)));
    }

    @Test
    void corsAllowsTheReactDevServerAndNobodyElse() throws Exception {
        mvc.perform(options("/api/transactions")
                        .header("Origin", "http://localhost:5173")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isOk())
                .andExpect(header().string("Access-Control-Allow-Origin", "http://localhost:5173"));

        mvc.perform(options("/api/transactions")
                        .header("Origin", "http://evil.example")
                        .header("Access-Control-Request-Method", "GET"))
                .andExpect(status().isForbidden());
    }

    // ---------- helpers ----------

    private static String newAccount() {
        return "acc-" + UUID.randomUUID();
    }

    // Saves a transaction with exactly the values a test wants to see in the list
    private Transaction save(String account, TransactionStatus status, Integer riskScore, Instant occurredAt) {
        Transaction t = new Transaction();
        t.setIdempotencyKey(UUID.randomUUID().toString());
        t.setRequestHash("hash");
        t.setAccountId(account);
        t.setTransactionType(TransactionType.PAYMENT);
        t.setAmount(new BigDecimal("100.00"));
        t.setCurrency("INR");
        t.setOccurredAt(occurredAt);
        t.setStatus(status);
        t.setRiskScore(riskScore);
        t.setReasonCodes("HIGH_VELOCITY");
        return repository.saveAndFlush(t);
    }

    private void review(UUID id, String action, String note, String reviewer) throws Exception {
        String body = "{\"action\":\"" + action + "\",\"note\":\"" + note + "\",\"reviewer\":\"" + reviewer + "\"}";
        mvc.perform(post("/api/transactions/" + id + "/review")
                        .contentType(MediaType.APPLICATION_JSON).content(body))
                .andExpect(status().isCreated());
    }
}