package com.fraudguard.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fasterxml.jackson.databind.JsonNode;
import com.fraudguard.dto.ModelUsageDTO;
import com.fraudguard.service.ModelInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Read-only model information for the dashboard (Phase 9).
@RestController
@Tag(name = "Feedback loop")
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class ModelInfoController {

    private final ModelInfoService service;

    // GET /api/feedback/models: transactions scored per model version (from our database)
    @Operation(summary = "Transactions scored per model version", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/models")
    public ModelUsageDTO models() {
        return service.modelUsage();
    }

    // GET /api/feedback/registry: loaded model, registry's active model, promotion history (from the ML service)
    @Operation(summary = "Live model and promotion history (from the ML service)", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/registry")
    public JsonNode registry() {
        return service.registry();
    }
}
