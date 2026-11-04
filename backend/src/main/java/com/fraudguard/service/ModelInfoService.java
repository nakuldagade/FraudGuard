package com.fraudguard.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.dto.ModelUsageDTO;
import com.fraudguard.repository.TransactionRepository;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;
import java.util.Comparator;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.server.ResponseStatusException;

// Phase 9: information about the ML models, for the dashboard's "Model feedback" card.
@Service
@RequiredArgsConstructor
public class ModelInfoService {

    private final TransactionRepository transactions;
    private final ObjectMapper objectMapper;

    // Same address the scoring client uses (application.yml: fraudguard.ml.base-url)
    @Value("${fraudguard.ml.base-url}")
    private String mlBaseUrl;

    // Sent as X-Admin-Token: the ML service protects /admin/* with it
    @Value("${fraudguard.ml.admin-token:}")
    private String mlAdminToken;

    // Initialised here, so Lombok leaves it out of the constructor
    private final HttpClient http = HttpClient.newBuilder()
            .connectTimeout(Duration.ofSeconds(2))
            .build();

    // From OUR database: how many transactions each model version scored. This is the real audit trail.
    @Transactional(readOnly = true)
    public ModelUsageDTO modelUsage() {
        List<ModelUsageDTO.Entry> entries = transactions.summarizeModelUsage().stream()
                .map(row -> new ModelUsageDTO.Entry(
                        (String) row[0],
                        ((Number) row[1]).longValue(),
                        (Instant) row[2]))
                .sorted(Comparator.comparing(ModelUsageDTO.Entry::lastScoredAt).reversed())
                .toList();
        return new ModelUsageDTO(entries, transactions.countByModelVersionIsNull());
    }

    // From the ML SERVICE: loaded model, registry's active model, promotion history.
    // Passed through as JSON. If the ML service is down, the dashboard card shows a note instead.
    public JsonNode registry() {
        try {
            HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(mlBaseUrl + "/admin/registry"))
                    .timeout(Duration.ofSeconds(2))
                    .GET();
            if (mlAdminToken != null && !mlAdminToken.isBlank()) {
                builder.header("X-Admin-Token", mlAdminToken);
            }
            HttpRequest request = builder.build();
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            if (response.statusCode() != 200) {
                throw new ResponseStatusException(HttpStatus.BAD_GATEWAY,
                        "ML service answered " + response.statusCode());
            }
            return objectMapper.readTree(response.body());
        } catch (IOException e) {
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "ML service unreachable");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ResponseStatusException(HttpStatus.SERVICE_UNAVAILABLE, "Interrupted while calling the ML service");
        }
    }
}
