package com.fraudguard.controller;

import com.fraudguard.dto.FeedbackStatsDTO;
import com.fraudguard.dto.LabeledTransactionDTO;
import com.fraudguard.service.FeedbackService;
import java.util.List;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

// Phase 9 feedback loop. Read-only: nothing here changes a decision or a review.
// (These endpoints expose training data, so Phase 10 should restrict them to an admin role.)
@RestController
@RequestMapping("/api/feedback")
@RequiredArgsConstructor
public class FeedbackController {

    private final FeedbackService service;

    // GET /api/feedback/labels: every transaction an analyst labelled fraud or legitimate,
    // with its stored decision inputs. The ML service's retraining script downloads this.
    // (Not paged: fine at demo scale; a real system would stream or page it.)
    @GetMapping("/labels")
    public List<LabeledTransactionDTO> labels() {
        return service.labeledTransactions();
    }

    // GET /api/feedback/stats: how much feedback exists
    @GetMapping("/stats")
    public FeedbackStatsDTO stats() {
        return service.stats();
    }
}