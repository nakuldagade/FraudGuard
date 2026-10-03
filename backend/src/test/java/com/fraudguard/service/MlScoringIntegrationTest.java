package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.dto.TransactionRequestDTO;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.TransactionRepository;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import org.hamcrest.Matchers;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

// The whole pipeline with the ML client switched ON, talking to a fake ML service whose answer
// each test can change. (Needs its own application context, so it starts its own containers.)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class MlScoringIntegrationTest {

    private static volatile int stubStatus = 200;
    private static volatile double stubScore = 0.68;
    private static final HttpServer ML_STUB = startStub();

    // Points the app at the fake ML service and switches the ML client on (the test profile disables it)
    @DynamicPropertySource
    static void mlProperties(DynamicPropertyRegistry registry) {
        registry.add("fraudguard.ml.enabled", () -> "true");
        registry.add("fraudguard.ml.base-url", () -> "http://127.0.0.1:" + ML_STUB.getAddress().getPort());
    }

    @Autowired TransactionService transactionService;
    @Autowired TransactionRepository repository;
    @Autowired MockMvc mvc;

    @BeforeEach
    void stubWorksAgain() {
        stubStatus = 200;
        stubScore = 0.68;
    }

    @AfterAll
    static void stopStub() {
        ML_STUB.stop(0);
    }

    @Test
    void scoreAndModelVersionAreStoredEvenWhenANewAccountMakesThePolicyIgnoreThem() throws Exception {
        stubScore = 0.77;
        UUID id = create("acc-ml-" + UUID.randomUUID(), null);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getModelVersion()).isEqualTo("isoforest-test"));
        assertThat(repository.findById(id).orElseThrow().getAnomalyScore()).isEqualTo(0.77);

        mvc.perform(get("/api/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomalyScore").value(0.77))
                .andExpect(jsonPath("$.modelVersion").value("isoforest-test"))
                .andExpect(jsonPath("$.status").value("APPROVED"))                      // brand-new account: ML ignored
                .andExpect(jsonPath("$.policyVersion").value("policy-v1"))
                .andExpect(jsonPath("$.reasonCodes[0]").value("ML_IGNORED_NEW_ACCOUNT"));
    }

    @Test
    void transactionIsStillDecidedByTheRulesWhenTheMlServiceFails() {
        stubStatus = 500;   // the fake ML service now fails every call
        UUID id = create("acc-ml-down-" + UUID.randomUUID(), null);

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(TransactionStatus.APPROVED));

        Transaction t = repository.findById(id).orElseThrow();
        assertThat(t.getRiskScore()).isZero();
        assertThat(t.getAnomalyScore()).isNull();
        assertThat(t.getModelVersion()).isNull();
        assertThat(t.getReasonCodes()).isEqualTo("ML_UNAVAILABLE");
        assertThat(t.getDecisionExplanation()).contains("rules only");
    }

    @Test
    void elevatedMlScoreAloneSendsAnEstablishedAccountToReview() {
        stubScore = 0.68;   // "elevated" (at least 0.66, below 0.70)
        String account = "acc-elevated-" + UUID.randomUUID();
        Instant start = Instant.now().minus(Duration.ofHours(2));

        // Six ordinary transactions, 7 minutes apart: no velocity rule fires, and from the 6th
        // on the account has enough history (5 earlier transactions) for the ML score to count
        UUID last = null;
        for (int i = 0; i < 6; i++) {
            last = create(account, start.plus(Duration.ofMinutes(7L * i)));
        }
        UUID decided = last;

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(decided).orElseThrow().getStatus()).isEqualTo(TransactionStatus.REVIEW));

        Transaction t = repository.findById(decided).orElseThrow();
        assertThat(t.getRiskScore()).isZero();                        // no rule fired
        assertThat(t.getReasonCodes()).isEqualTo("ML_ANOMALY_ELEVATED");
        assertThat(t.getDecisionExplanation()).contains("the ML score alone never blocks");
        assertThat(t.getDecisionInputs()).contains("\"mlSignal\":\"ELEVATED\"").contains("policy-v1");
    }

    @Test
    void veryHighMlScoreOnTopOfARuleReviewBlocksAndExplainsWhy() throws Exception {
        stubScore = 0.75;   // "very high" (at least 0.70)
        String account = "acc-block-" + UUID.randomUUID();

        // Six transactions in a row: the 6th breaks the limit of 5 per 5 minutes (HIGH_VELOCITY = rules say REVIEW),
        // and the account has 5 earlier transactions, so the ML score counts
        UUID last = null;
        for (int i = 0; i < 6; i++) {
            last = create(account, null);
        }
        UUID decided = last;

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(decided).orElseThrow().getStatus()).isEqualTo(TransactionStatus.BLOCKED));

        Transaction t = repository.findById(decided).orElseThrow();
        assertThat(t.getRiskScore()).isEqualTo(40);
        assertThat(t.getReasonCodes()).isEqualTo("HIGH_VELOCITY,ML_ANOMALY_VERY_HIGH,RULES_AND_ML_AGREE");

        mvc.perform(get("/api/transactions/" + decided))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("BLOCKED"))
                .andExpect(jsonPath("$.reasonCodes[2]").value("RULES_AND_ML_AGREE"))
                .andExpect(jsonPath("$.decisionExplanation").value(Matchers.containsString("very high")));
    }

    // ---------- helpers ----------

    private UUID create(String account, Instant occurredAt) {
        var request = new TransactionRequestDTO(account, "m-1", TransactionType.PAYMENT,
                new BigDecimal("100.00"), "INR", "dev-1", "IN", occurredAt);
        return transactionService.create(UUID.randomUUID().toString(), request).transaction().id();
    }

    private static HttpServer startStub() {
        try {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/score", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = ("{\"transaction_id\":\"x\",\"anomaly_score\":" + stubScore
                        + ",\"model_version\":\"isoforest-test\",\"feature_schema_version\":\"1\"}")
                        .getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().add("Content-Type", "application/json");
                exchange.sendResponseHeaders(stubStatus, body.length);
                try (OutputStream out = exchange.getResponseBody()) {
                    out.write(body);
                }
            });
            stub.start();
            return stub;
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}