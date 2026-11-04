package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionType;
import com.sun.net.httpserver.HttpServer;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

// The four call outcomes, the circuit gauge, and the service token header, against a fake ML service.
class MlServiceClientMetricsTest {

    private static final String OK_JSON = """
            {"transaction_id":"t","anomaly_score":0.5,"model_version":"m-v1","feature_schema_version":"1"}""";

    private HttpServer server;
    private volatile int status = 200;
    private final AtomicReference<String> tokenHeader = new AtomicReference<>();
    private final SimpleMeterRegistry registry = new SimpleMeterRegistry();
    private final AtomicLong now = new AtomicLong(1_000_000);   // the clock the test controls

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/score", exchange -> {
            tokenHeader.set(exchange.getRequestHeaders().getFirst("X-Service-Token"));
            byte[] body = OK_JSON.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(status, body.length);
            try (OutputStream out = exchange.getResponseBody()) {
                out.write(body);
            }
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private MlServiceClient client(boolean enabled) {
        return new MlServiceClient(enabled, "http://127.0.0.1:" + server.getAddress().getPort(),
                Duration.ofSeconds(2), 3, Duration.ofSeconds(30), now::get, registry, "secret-token");
    }

    private double calls(String outcome) {
        var c = registry.find("fraudguard.ml.calls").tag("outcome", outcome).counter();
        return c == null ? 0 : c.count();
    }

    private double circuit() {
        return registry.get("fraudguard.ml.circuit.open").gauge().value();
    }

    @Test
    void successIsCountedTimedAndSendsTheServiceToken() {
        assertThat(client(true).score(payload(), features())).isPresent();

        assertThat(calls("success")).isEqualTo(1);
        assertThat(registry.get("fraudguard.ml.call").tag("outcome", "success").timer().count()).isEqualTo(1);
        assertThat(tokenHeader.get()).isEqualTo("secret-token");
    }

    @Test
    void disabledClientCountsDisabledAndNeverCallsOut() {
        assertThat(client(false).score(payload(), features())).isEmpty();

        assertThat(calls("disabled")).isEqualTo(1);
        assertThat(tokenHeader.get()).isNull();
    }

    @Test
    void failuresOpenTheCircuitAndTheGaugeFlipsThenFlipsBackAfterTheOpenWindow() {
        MlServiceClient client = client(true);
        status = 500;
        for (int i = 0; i < 3; i++) {
            assertThat(client.score(payload(), features())).isEmpty();
        }
        assertThat(calls("failure")).isEqualTo(3);
        assertThat(circuit()).isEqualTo(1.0);                 // open after 3 consecutive failures

        assertThat(client.score(payload(), features())).isEmpty();
        assertThat(calls("circuit_open")).isEqualTo(1);       // skipped, not attempted
        assertThat(calls("failure")).isEqualTo(3);

        now.addAndGet(30_001);                                // the open window has passed
        assertThat(circuit()).isEqualTo(0.0);
        status = 200;
        assertThat(client.score(payload(), features())).isPresent();
        assertThat(calls("success")).isEqualTo(1);            // the trial call succeeded: circuit stays closed
        assertThat(circuit()).isEqualTo(0.0);
    }

    private static TransactionEvent.Payload payload() {
        return new TransactionEvent.Payload(UUID.randomUUID(), "acc-1", "m-1", TransactionType.PAYMENT,
                new BigDecimal("5.00"), "INR", "d", "IN", Instant.parse("2026-10-01T14:30:00Z"));
    }

    private static VelocityFeatures features() {
        return new VelocityFeatures(1, 1, BigDecimal.TEN, BigDecimal.TEN, 0,
                BigDecimal.ZERO, BigDecimal.ZERO, null, false, false, null, false);
    }
}
