package com.fraudguard.controller;

import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import com.fraudguard.dto.SystemStatusDTO;
import com.fraudguard.service.DlqService;
import com.fraudguard.service.RedriveService;
import com.fraudguard.service.SystemStatusService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Operator tools. Every route here is ADMIN-only (enforced in the security config, Phase 10.4).
@RestController
@Tag(name = "Admin", description = "Operator tools. ADMIN role only.")
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final int MAX_REDRIVE = 5_000;

    private final RedriveService redriveService;
    private final DlqService dlqService;
    private final SystemStatusService statusService;

    // POST /api/admin/transactions/redrive-pending?olderThanSeconds=120&limit=500
    @Operation(summary = "Re-queue transactions stuck in PENDING", description = "Writes a new outbox event for each stuck transaction. Safe to repeat.", security = @SecurityRequirement(name = "bearerAuth"))
    @PostMapping("/transactions/redrive-pending")
    public RedriveService.Result redrivePending(
            @RequestParam(defaultValue = "120") long olderThanSeconds,
            @RequestParam(defaultValue = "500") int limit) {
        return redriveService.redrivePending(
                Duration.ofSeconds(Math.max(olderThanSeconds, 0)), Math.min(Math.max(limit, 1), MAX_REDRIVE));
    }

    // GET /api/admin/system-status: dependency health, ML circuit and backlogs for the Operations tab
    @Operation(summary = "System status: dependencies, ML circuit, backlogs", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/system-status")
    public SystemStatusDTO systemStatus() {
        return statusService.status();
    }

    // GET /api/admin/dlq/summary
    @Operation(summary = "Dead-letter topic size and recent failure causes", security = @SecurityRequirement(name = "bearerAuth"))
    @GetMapping("/dlq/summary")
    public DlqService.Summary dlqSummary() {
        return dlqService.summarize();
    }
}
