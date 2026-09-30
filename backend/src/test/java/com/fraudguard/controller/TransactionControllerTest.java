package com.fraudguard.controller;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.jayway.jsonpath.JsonPath;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

@SpringBootTest          // start the whole application
@AutoConfigureMockMvc    // gives us MockMvc: call endpoints without opening a real HTTP port
@ActiveProfiles("test")  // use the "test" section of application.yml
@Testcontainers
class TransactionControllerTest {

    // A throwaway PostgreSQL in Docker. @ServiceConnection points the app's datasource at it,
    // and Flyway runs our V1 migration against it automatically.
    @Container
    @ServiceConnection
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:16-alpine");

    @Autowired
    MockMvc mvc;

    private static final String BODY = """
            {"accountId":"acc-1","merchantId":"m-9","transactionType":"PAYMENT",
             "amount":1250.50,"currency":"INR","deviceId":"dev-1","country":"IN"}""";

    // Same as BODY but a different amount
    private static final String OTHER_BODY = BODY.replace("1250.50", "9999.00");

    @Test
    void createsPendingTransaction() throws Exception {
        mvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted())                    // 202
                .andExpect(header().exists("Location"))
                .andExpect(jsonPath("$.id").exists())
                .andExpect(jsonPath("$.status").value("PENDING"))
                .andExpect(jsonPath("$.accountId").value("acc-1"));
    }

    @Test
    void sameKeySameBodyReturnsSameTransaction() throws Exception {
        String key = UUID.randomUUID().toString();
        var first = mvc.perform(post("/api/transactions").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(first.getResponse().getContentAsString(), "$.id");

        // Retry with the same key: 200 and the SAME id, no duplicate row
        mvc.perform(post("/api/transactions").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id));
    }

    @Test
    void sameKeyDifferentBodyIsConflict() throws Exception {
        String key = UUID.randomUUID().toString();
        mvc.perform(post("/api/transactions").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted());

        mvc.perform(post("/api/transactions").header("Idempotency-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(OTHER_BODY))
                .andExpect(status().isConflict())                    // 409
                .andExpect(jsonPath("$.title").value("Idempotency conflict"));
    }

    @Test
    void invalidBodyReturnsFieldErrors() throws Exception {
        String bad = """
                {"accountId":"","transactionType":"PAYMENT","amount":-5,"currency":"rupees"}""";
        mvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest())                  // 400
                .andExpect(jsonPath("$.errors.accountId").exists())
                .andExpect(jsonPath("$.errors.amount").exists())
                .andExpect(jsonPath("$.errors.currency").exists());
    }

    @Test
    void unknownTransactionTypeIsBadRequest() throws Exception {
        String bad = BODY.replace("PAYMENT", "GIFT");
        mvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(bad))
                .andExpect(status().isBadRequest());
    }

    @Test
    void missingIdempotencyKeyIsBadRequest() throws Exception {
        mvc.perform(post("/api/transactions")
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isBadRequest());
    }

    @Test
    void retrievesStoredTransaction() throws Exception {
        var created = mvc.perform(post("/api/transactions")
                        .header("Idempotency-Key", UUID.randomUUID().toString())
                        .contentType(MediaType.APPLICATION_JSON).content(BODY))
                .andExpect(status().isAccepted()).andReturn();
        String id = JsonPath.read(created.getResponse().getContentAsString(), "$.id");

        mvc.perform(get("/api/transactions/" + id))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.id").value(id))
                .andExpect(jsonPath("$.amount").value(1250.50));
    }

    @Test
    void unknownTransactionIsNotFound() throws Exception {
        mvc.perform(get("/api/transactions/" + UUID.randomUUID()))
                .andExpect(status().isNotFound());                   // 404
    }
}