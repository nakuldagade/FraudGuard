package com.fraudguard.controller;

import com.fraudguard.service.DlqService;
import com.fraudguard.service.RedriveService;
import java.time.Duration;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

// Operator tools. Every route here is ADMIN-only (enforced in the security config, Phase 10.4).
@RestController
@RequestMapping("/api/admin")
@RequiredArgsConstructor
public class AdminController {

    private static final int MAX_REDRIVE = 5_000;

    private final RedriveService redriveService;
    private final DlqService dlqService;

    // POST /api/admin/transactions/redrive-pending?olderThanSeconds=120&limit=500
    @PostMapping("/transactions/redrive-pending")
    public RedriveService.Result redrivePending(
            @RequestParam(defaultValue = "120") long olderThanSeconds,
            @RequestParam(defaultValue = "500") int limit) {
        return redriveService.redrivePending(
                Duration.ofSeconds(Math.max(olderThanSeconds, 0)), Math.min(Math.max(limit, 1), MAX_REDRIVE));
    }

    // GET /api/admin/dlq/summary
    @GetMapping("/dlq/summary")
    public DlqService.Summary dlqSummary() {
        return dlqService.summarize();
    }
}
