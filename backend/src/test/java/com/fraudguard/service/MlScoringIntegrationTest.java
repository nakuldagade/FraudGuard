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
import java.util.UUID;
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

// The whole pipeline with the ML client switched ON, talking to a fake ML service.
// (This class needs its own application context, so it starts its own containers: a bit slower.)
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class MlScoringIntegrationTest {

    private static volatile int stubStatus = 200;
    private static final String SCORE_JSON = """
            {"transaction_id":"x","anomaly_score":0.77,"model_version":"isoforest-test","feature_schema_version":"1"}""";
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
    }

    @AfterAll
    static void stopStub() {
        ML_STUB.stop(0);
    }

    @Test
    void scoreAndModelVersionAreStoredAndReturnedByTheApi() throws Exception {
        UUID id = create("acc-ml-" + UUID.randomUUID());

        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getModelVersion()).isEqualTo("isoforest-test"));
        assertThat(repository.findById(id).orElseThrow().getAnomalyScore()).isEqualTo(0.77);

        mvc.perform(get("/api/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.anomalyScore").value(0.77))
                .andExpect(jsonPath("$.modelVersion").value("isoforest-test"));
    }

    @Test
    void transactionIsStillDecidedByTheRulesWhenTheMlServiceFails() {
        stubStatus = 500;   // the fake ML service now fails every call
        UUID id = create("acc-ml-down-" + UUID.randomUUID());

        // The transaction still gets a verdict from the rules...
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(repository.findById(id).orElseThrow().getStatus()).isEqualTo(TransactionStatus.APPROVED));

        // ...but has no ML score or model version
        Transaction t = repository.findById(id).orElseThrow();
        assertThat(t.getRiskScore()).isZero();
        assertThat(t.getAnomalyScore()).isNull();
        assertThat(t.getModelVersion()).isNull();
    }

    // ---------- helpers ----------

    private UUID create(String account) {
        var request = new TransactionRequestDTO(account, "m-1", TransactionType.PAYMENT,
                new BigDecimal("100.00"), "INR", "dev-1", "IN", null);
        return transactionService.create(UUID.randomUUID().toString(), request).transaction().id();
    }

    private static HttpServer startStub() {
        try {
            HttpServer stub = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            stub.createContext("/score", exchange -> {
                exchange.getRequestBody().readAllBytes();
                byte[] body = SCORE_JSON.getBytes(StandardCharsets.UTF_8);
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