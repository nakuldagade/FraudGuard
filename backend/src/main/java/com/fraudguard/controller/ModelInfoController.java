package com.fraudguard.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fraudguard.dto.ModelUsageDTO;
import com.fraudguard.service.ModelInfoService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Read-only model information for the dashboard (Phase 9).
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class ModelInfoController {

    private final ModelInfoService service;

    // GET /api/feedback/models: transactions scored per model version (from our database)
    @GetMapping("/models")
    public ModelUsageDTO models() {
        return service.modelUsage();
    }

    // GET /api/feedback/registry: loaded model, registry's active model, promotion history (from the ML service)
    @GetMapping("/registry")
    public JsonNode registry() {
        return service.registry();
    }
}
