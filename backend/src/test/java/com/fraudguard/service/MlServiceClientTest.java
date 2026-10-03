package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudguard.dto.MlScoreResponse;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionType;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// No Spring and no Docker: a small fake web server (built into the JDK) plays the ML service.
class MlServiceClientTest {

    private static final String OK_JSON = """
            {"transaction_id":"t","anomaly_score":0.81,"model_version":"isoforest-v1","feature_schema_version":"1"}""";

    private HttpServer server;
    private final AtomicInteger requests = new AtomicInteger();          // how many calls reached the fake
    private final AtomicReference<String> lastBody = new AtomicReference<>();
    private volatile int status = 200;
    private volatile long delayMillis = 0;
    private volatile String responseBody = OK_JSON;

    @BeforeEach
    void startFakeMlService() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);   // port 0 = any free port
        server.createContext("/score", exchange -> {
            requests.incrementAndGet();
            lastBody.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            try {
                Thread.sleep(delayMillis);                                       // simulate a slow service
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            byte[] body = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stopFakeMlService() {
        server.stop(0);
    }

    @Test
    void scoreIsReturnedAndRequestUsesTheNamesThePythonServiceExpects() {
        Optional<MlScoreResponse> result = client(Duration.ofSeconds(2)).score(payload(), features());

        assertThat(result).isPresent();
        assertThat(result.get().anomalyScore()).isEqualTo(0.81);
        assertThat(result.get().modelVersion()).isEqualTo("isoforest-v1");
        assertThat(lastBody.get()).contains(
                "\"hour_of_day\":14", "\"transaction_count_5m\":7", "\"new_device\":true",
                "\"country_changed\":true", "\"amount_z_score\":2619.96", "\"seconds_since_previous\":5");
    }

    @Test
    void missingHistoryIsSentAsNull() {
        VelocityFeatures firstEver = new VelocityFeatures(1, 1, BigDecimal.TEN, BigDecimal.TEN, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, null, true, true, null, false);

        client(Duration.ofSeconds(2)).score(payload(), firstEver);

        assertThat(lastBody.get()).contains("\"amount_z_score\":null", "\"seconds_since_previous\":null");
    }

    @Test
    void serverErrorFallsBackToEmpty() {
        status = 500;

        assertThat(client(Duration.ofSeconds(2)).score(payload(), features())).isEmpty();
    }

    @Test
    void slowServiceTimesOutAndFallsBackToEmpty() {
        delayMillis = 1500;   // the fake answers after 1.5s, but the client only waits 300ms

        assertThat(client(Duration.ofMillis(300)).score(payload(), features())).isEmpty();
    }

    @Test
    void unreachableServiceFallsBackToEmpty() {
        MlServiceClient client = client(Duration.ofSeconds(2));
        server.stop(0);   // nothing is listening on that port any more

        assertThat(client.score(payload(), features())).isEmpty();
    }

    @Test
    void invalidResponseFallsBackToEmpty() {
        responseBody = "{\"transaction_id\":\"t\",\"model_version\":\"isoforest-v1\"}";   // no anomaly_score

        assertThat(client(Duration.ofSeconds(2)).score(payload(), features())).isEmpty();
    }

    @Test
    void circuitOpensAfterRepeatedFailuresAndClosesAgainOnSuccess() {
        AtomicLong now = new AtomicLong(1_000_000);   // a fake clock the test controls
        MlServiceClient client = new MlServiceClient(true, baseUrl(), Duration.ofSeconds(2), 3,
                Duration.ofSeconds(30), now::get);
        status = 500;

        // Three failures in a row open the circuit
        for (int i = 0; i < 3; i++) {
            assertThat(client.score(payload(), features())).isEmpty();
        }
        assertThat(requests.get()).isEqualTo(3);

        // While it is open the fake isn't even called
        assertThat(client.score(payload(), features())).isEmpty();
        assertThat(requests.get()).isEqualTo(3);

        // After the pause one trial call goes through; it fails, so the circuit reopens at once
        now.addAndGet(31_000);
        assertThat(client.score(payload(), features())).isEmpty();
        assertThat(requests.get()).isEqualTo(4);
        assertThat(client.score(payload(), features())).isEmpty();
        assertThat(requests.get()).isEqualTo(4);

        // The service recovers: the next trial succeeds and everything is back to normal
        status = 200;
        now.addAndGet(31_000);
        assertThat(client.score(payload(), features())).isPresent();
        assertThat(client.score(payload(), features())).isPresent();
        assertThat(requests.get()).isEqualTo(6);
    }

    @Test
    void disabledClientNeverCallsTheService() {
        MlServiceClient disabled = new MlServiceClient(false, baseUrl(), Duration.ofSeconds(2), 3,
                Duration.ofSeconds(30), System::currentTimeMillis);

        assertThat(disabled.score(payload(), features())).isEmpty();
        assertThat(requests.get()).isZero();
    }

    // ---------- helpers ----------

    private String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private MlServiceClient client(Duration timeout) {
        return new MlServiceClient(true, baseUrl(), timeout, 3, Duration.ofSeconds(30), System::currentTimeMillis);
    }

    private static TransactionEvent.Payload payload() {
        return new TransactionEvent.Payload(UUID.randomUUID(), "acc-1", "m-1", TransactionType.PAYMENT,
                new BigDecimal("5000.00"), "INR", "dev-9", "US", Instant.parse("2026-10-01T14:30:00Z"));
    }

    private static VelocityFeatures features() {
        return new VelocityFeatures(7, 7, new BigDecimal("5591.00"), new BigDecimal("5591.00"), 6,
                new BigDecimal("98.50"), new BigDecimal("1.87"), 2619.96, true, true, 5L, true);
    }
}