package com.fraudguard.security;

import static com.fraudguard.TestAuth.INGEST_KEY;
import static com.fraudguard.TestAuth.admin;
import static com.fraudguard.TestAuth.analyst;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.httpBasic;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.config.TestcontainersConfig;
import com.fraudguard.entity.Transaction;
import com.fraudguard.entity.TransactionStatus;
import com.fraudguard.entity.TransactionType;
import com.fraudguard.repository.AnalystReviewRepository;
import com.fraudguard.repository.TransactionRepository;
import io.micrometer.core.instrument.MeterRegistry;
import java.math.BigDecimal;
import java.time.Instant;
import java.util.Base64;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

// The security rules of the whole API, checked route by route. This test deliberately uses the plain
// MockMvc request builders (no default credentials) so "not logged in" really means not logged in.
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
@Import(TestcontainersConfig.class)
class SecurityApiTest {

    private static final String VALID_TX = """
            {"accountId":"acc-sec","transactionType":"DEPOSIT","amount":10.50,"currency":"USD"}""";
    private static final String REVIEW = "{\"action\":\"CONFIRM_FRAUD\"}";

    @Autowired MockMvc mvc;
    @Autowired JwtService jwtService;
    @Autowired ObjectMapper objectMapper;
    @Autowired TransactionRepository transactions;
    @Autowired AnalystReviewRepository reviews;
    @Autowired MeterRegistry metrics;

    private static final AtomicInteger NEXT_IP = new AtomicInteger(1);

    // Each login test uses its own client address, so the 5-per-minute limit of one test never affects another
    private static RequestPostProcessor freshIp() {
        String ip = "10.9.0." + NEXT_IP.getAndIncrement();
        return request -> {
            request.setRemoteAddr(ip);
            return request;
        };
    }

    // ---------- who may call what ----------

    private record Route(String name, Supplier<MockHttpServletRequestBuilder> request) {
    }

    private Route[] analystRoutes() {
        String id = UUID.randomUUID().toString();   // unknown id: an allowed caller gets 404, which is still "allowed"
        return new Route[] {
                new Route("GET /api/transactions", () -> get("/api/transactions")),
                new Route("GET /api/transactions/summary", () -> get("/api/transactions/summary")),
                new Route("GET /api/transactions/{id}", () -> get("/api/transactions/" + id)),
                new Route("GET /api/transactions/{id}/detail", () -> get("/api/transactions/" + id + "/detail")),
                new Route("POST /api/transactions/{id}/review", () -> post("/api/transactions/" + id + "/review")
                        .contentType(MediaType.APPLICATION_JSON).content(REVIEW)),
                new Route("GET /api/feedback/stats", () -> get("/api/feedback/stats")),
                new Route("GET /api/feedback/models", () -> get("/api/feedback/models")),
                new Route("GET /api/feedback/registry", () -> get("/api/feedback/registry")),
                new Route("GET /api/auth/me", () -> get("/api/auth/me")),
        };
    }

    private Route[] adminRoutes() {
        return new Route[] {
                new Route("GET /api/feedback/labels", () -> get("/api/feedback/labels")),
                new Route("POST /api/admin/transactions/redrive-pending", () -> post("/api/admin/transactions/redrive-pending")),
                new Route("GET /api/admin/dlq/summary", () -> get("/api/admin/dlq/summary")),
                new Route("GET /api/admin/system-status", () -> get("/api/admin/system-status")),
                new Route("GET /api/admin/anything-else", () -> get("/api/admin/anything-else")),
        };
    }

    @Test
    void everyProtectedRouteReturns401WithoutCredentials() throws Exception {
        for (Route r : concat(analystRoutes(), adminRoutes())) {
            MvcResult result = mvc.perform(copy(r)).andReturn();
            assertThat(result.getResponse().getStatus()).as(r.name()).isEqualTo(401);
        }
        mvc.perform(post("/api/transactions").contentType(MediaType.APPLICATION_JSON).content(VALID_TX)
                        .header("Idempotency-Key", "k-" + UUID.randomUUID()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void analystCanUseAnalystRoutesButNotAdminRoutes() throws Exception {
        for (Route r : analystRoutes()) {
            int code = mvc.perform(copy(r).with(analyst("a1"))).andReturn().getResponse().getStatus();
            assertThat(code).as(r.name()).isNotIn(401, 403);
        }
        for (Route r : adminRoutes()) {
            int code = mvc.perform(copy(r).with(analyst("a1"))).andReturn().getResponse().getStatus();
            assertThat(code).as(r.name()).isEqualTo(403);
        }
    }

    @Test
    void adminCanUseEverything() throws Exception {
        for (Route r : concat(analystRoutes(), adminRoutes())) {
            int code = mvc.perform(copy(r).with(admin("root"))).andReturn().getResponse().getStatus();
            assertThat(code).as(r.name()).isNotIn(401, 403);
        }
    }

    @Test
    void ingestionNeedsTheServiceApiKeyAndNotALogin() throws Exception {
        Supplier<MockHttpServletRequestBuilder> base = () -> post("/api/transactions")
                .contentType(MediaType.APPLICATION_JSON).content(VALID_TX)
                .header("Idempotency-Key", "k-" + UUID.randomUUID());
        // a logged-in person is not a service: 403
        mvc.perform(base.get().with(admin("root")))
                .andExpect(status().isForbidden());
        // wrong key: 401
        mvc.perform(base.get().header("X-API-Key", "nope"))
                .andExpect(status().isUnauthorized());
        // right key: accepted
        mvc.perform(base.get().header("X-API-Key", INGEST_KEY))
                .andExpect(status().isAccepted());
    }

    @Test
    void apiKeyOpensOnlyTheIngestionRoute() throws Exception {
        mvc.perform(get("/api/transactions").header("X-API-Key", INGEST_KEY)).andExpect(status().isUnauthorized());
    }

    @Test
    void unauthorizedAndForbiddenAnswersAreJsonProblemDetails() throws Exception {
        mvc.perform(get("/api/transactions"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(401))
                .andExpect(jsonPath("$.title").value("Unauthorized"));
        mvc.perform(get("/api/admin/dlq/summary").with(analyst("a1")))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status").value(403));
    }

    // ---------- tokens ----------

    @Test
    void tamperedTokenSignatureIsRejected() throws Exception {
        String token = jwtService.issue("test-analyst", "ANALYST");
        String tampered = token.substring(0, token.length() - 3) + (token.endsWith("AAA") ? "BBB" : "AAA");
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + tampered))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void changingTheRoleInsideATokenBreaksItsSignature() throws Exception {
        String[] parts = jwtService.issue("test-analyst", "ANALYST").split("\\.");
        String payload = new String(Base64.getUrlDecoder().decode(parts[1])).replace("ANALYST", "ADMIN");
        String forged = parts[0] + "." + Base64.getUrlEncoder().withoutPadding().encodeToString(payload.getBytes()) + "." + parts[2];
        mvc.perform(get("/api/feedback/labels").header("Authorization", "Bearer " + forged))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void expiredTokenIsRejected() throws Exception {
        String expired = jwtService.issue("test-analyst", "ANALYST", Instant.now().minusSeconds(2 * 3600));
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + expired))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void tokenSignedWithAnotherSecretIsRejected() throws Exception {
        var otherKey = new javax.crypto.spec.SecretKeySpec(
                "another-secret-another-secret-another!".getBytes(), "HmacSHA256");
        var encoder = new org.springframework.security.oauth2.jwt.NimbusJwtEncoder(
                new com.nimbusds.jose.jwk.source.ImmutableSecret<>(otherKey));
        var claims = org.springframework.security.oauth2.jwt.JwtClaimsSet.builder().subject("x").claim("role", "ADMIN")
                .issuedAt(Instant.now()).expiresAt(Instant.now().plusSeconds(600)).build();
        String foreign = encoder.encode(org.springframework.security.oauth2.jwt.JwtEncoderParameters.from(
                org.springframework.security.oauth2.jwt.JwsHeader.with(
                        org.springframework.security.oauth2.jose.jws.MacAlgorithm.HS256).build(), claims)).getTokenValue();
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + foreign))
                .andExpect(status().isUnauthorized());
    }

    // ---------- login ----------

    private String login(String username, String password, RequestPostProcessor ip) throws Exception {
        return mvc.perform(post("/api/auth/login").with(ip).contentType(MediaType.APPLICATION_JSON)
                        .content(objectMapper.writeValueAsString(new AuthController.LoginRequest(username, password))))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }

    @Test
    void loginReturnsATokenThatWorksEndToEnd() throws Exception {
        var ip = freshIp();
        var body = objectMapper.readTree(login("test-analyst", "test-analyst-password", ip));
        assertThat(body.get("role").asText()).isEqualTo("ANALYST");
        assertThat(body.get("username").asText()).isEqualTo("test-analyst");
        assertThat(body.get("expiresInSeconds").asLong()).isEqualTo(3600);
        String token = body.get("accessToken").asText();

        mvc.perform(get("/api/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.username").value("test-analyst"))
                .andExpect(jsonPath("$.role").value("ANALYST"));
        mvc.perform(get("/api/transactions").header("Authorization", "Bearer " + token)).andExpect(status().isOk());
        mvc.perform(get("/api/feedback/labels").header("Authorization", "Bearer " + token))
                .andExpect(status().isForbidden());
    }

    @Test
    void unknownUserAndWrongPasswordGetTheSameAnswer() throws Exception {
        var ip = freshIp();
        String wrongPassword = mvc.perform(post("/api/auth/login").with(ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-analyst\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        String unknownUser = mvc.perform(post("/api/auth/login").with(ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"nobody\",\"password\":\"nope\"}"))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(wrongPassword).isEqualTo(unknownUser);
        assertThat(wrongPassword).doesNotContain("nobody").doesNotContain("test-analyst");
    }

    @Test
    void loginIsLimitedToFiveAttemptsPerMinutePerIp() throws Exception {
        var ip = freshIp();
        for (int i = 0; i < 5; i++) {
            mvc.perform(post("/api/auth/login").with(ip).contentType(MediaType.APPLICATION_JSON)
                            .content("{\"username\":\"x\",\"password\":\"y\"}"))
                    .andExpect(status().isUnauthorized());
        }
        mvc.perform(post("/api/auth/login").with(ip).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"test-analyst\",\"password\":\"test-analyst-password\"}"))
                .andExpect(status().isTooManyRequests());
        // another address is not affected
        login("test-analyst", "test-analyst-password", freshIp());
        assertThat(metrics.get("fraudguard.auth.logins").tag("outcome", "rate_limited").counter().count())
                .isGreaterThanOrEqualTo(1.0);
    }

    @Test
    void loginCountsOutcomesWithoutRecordingThePassword() throws Exception {
        double before = count("success");
        login("test-admin", "test-admin-password", freshIp());
        assertThat(count("success")).isEqualTo(before + 1);
    }

    private double count(String outcome) {
        var counter = metrics.find("fraudguard.auth.logins").tag("outcome", outcome).counter();
        return counter == null ? 0 : counter.count();
    }

    // ---------- reviewer comes from the token ----------

    @Test
    void reviewerIsTheLoggedInUserNotTheRequestBody() throws Exception {
        Transaction t = new Transaction();
        t.setIdempotencyKey("sec-" + UUID.randomUUID());
        t.setRequestHash("h".repeat(64));
        t.setAccountId("acc-sec-review");
        t.setTransactionType(TransactionType.DEPOSIT);
        t.setAmount(new BigDecimal("5.00"));
        t.setCurrency("USD");
        t.setOccurredAt(Instant.now());
        t.setStatus(TransactionStatus.REVIEW);
        t = transactions.saveAndFlush(t);

        String token = objectMapper.readTree(login("test-oracle", "test-oracle-password", freshIp()))
                .get("accessToken").asText();
        mvc.perform(post("/api/transactions/" + t.getId() + "/review").header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"action\":\"MARK_LEGITIMATE\",\"reviewer\":\"someone-else\"}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.reviewer").value("test-oracle"));
        assertThat(reviews.findByTransactionIdOrderByCreatedAtDesc(t.getId()).get(0).getReviewer())
                .isEqualTo("test-oracle");
    }

    // ---------- actuator ----------

    @Test
    void healthProbesArePublicButDetailsAndMetricsAreNot() throws Exception {
        mvc.perform(get("/actuator/health")).andExpect(status().isOk())
                .andExpect(jsonPath("$.components").doesNotExist());           // no details for anonymous callers
        mvc.perform(get("/actuator/health/liveness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/health/readiness")).andExpect(status().isOk());
        mvc.perform(get("/actuator/metrics")).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(httpBasic("prometheus", "wrong"))).andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/metrics").with(httpBasic("prometheus", "test-metrics-password")))
                .andExpect(status().isOk());
        mvc.perform(get("/actuator/health").with(httpBasic("prometheus", "test-metrics-password")))
                .andExpect(jsonPath("$.components").exists());                  // details for the METRICS login
    }

    @Test
    void aDashboardTokenDoesNotOpenTheActuator() throws Exception {
        String token = jwtService.issue("root", "ADMIN");
        mvc.perform(get("/actuator/metrics").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized());
    }

    // ---------- helpers ----------

    private static MockHttpServletRequestBuilder copy(Route r) {
        return r.request().get();      // a fresh builder each time: builders remember .with(...) calls
    }

    private static Route[] concat(Route[] a, Route[] b) {
        Route[] all = java.util.Arrays.copyOf(a, a.length + b.length);
        System.arraycopy(b, 0, all, a.length, b.length);
        return all;
    }
}
