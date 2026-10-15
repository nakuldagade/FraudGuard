package com.fraudguard.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.dto.FeedbackStatsDTO;
import com.fraudguard.dto.LabeledTransactionDTO;
import com.fraudguard.entity.AnalystReview;
import com.fraudguard.entity.ReviewAction;
import com.fraudguard.entity.ReviewState;
import com.fraudguard.entity.Transaction;
import com.fraudguard.repository.AnalystReviewRepository;
import com.fraudguard.repository.TransactionRepository;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

// Phase 9: turns analyst reviews into labelled training data.
//
// Label rules:
//   * the NEWEST review of a transaction is its verdict
//   * CONFIRM_FRAUD -> 1, MARK_LEGITIMATE -> 0, REQUEST_FURTHER_REVIEW -> no label yet
//   * transactions nobody reviewed are NOT labelled "legitimate" (absence of a review is not a verdict)
//
// Known limitation (selection bias): analysts mostly see REVIEW/BLOCKED transactions, so the labelled
// set over-represents risky cases and contains few approved ones.
@Service
@RequiredArgsConstructor
public class FeedbackService {

    private final AnalystReviewRepository reviews;
    private final TransactionRepository transactions;
    private final ObjectMapper objectMapper;

    // Labelled examples, oldest payment first (so the trainer can split by time).
    @Transactional(readOnly = true)
    public List<LabeledTransactionDTO> labeledTransactions() {
        // transactionId -> the review that decides its label (only fraud / legitimate verdicts)
        Map<UUID, AnalystReview> decisive = latestReviewPerTransaction().values().stream()
                .filter(r -> labelOf(r.getAction()) != null)
                .collect(Collectors.toMap(AnalystReview::getTransactionId, r -> r));

        return transactions.findAllById(decisive.keySet()).stream()
                .map(t -> toDto(t, decisive.get(t.getId())))
                .filter(dto -> dto.decisionInputs() != null)       // a row without a usable snapshot cannot be trained on
                .sorted(Comparator.comparing(LabeledTransactionDTO::occurredAt))
                .toList();
    }

    @Transactional(readOnly = true)
    public FeedbackStatsDTO stats() {
        var latest = latestReviewPerTransaction().values();
        long fraud = latest.stream().filter(r -> r.getAction() == ReviewAction.CONFIRM_FRAUD).count();
        long legit = latest.stream().filter(r -> r.getAction() == ReviewAction.MARK_LEGITIMATE).count();
        long further = latest.stream().filter(r -> r.getAction() == ReviewAction.REQUEST_FURTHER_REVIEW).count();
        long unreviewed = transactions.countByReviewState(ReviewState.UNREVIEWED);
        return new FeedbackStatsDTO(fraud, legit, further, unreviewed, fraud + legit);
    }

    // ---- helpers ----

    // One review per transaction: the newest. (The query already does this; the merge function is a
    // safety net for the unlikely case of two reviews with the identical timestamp.)
    private Map<UUID, AnalystReview> latestReviewPerTransaction() {
        return reviews.findLatestPerTransaction().stream()
                .collect(Collectors.toMap(
                        AnalystReview::getTransactionId,
                        r -> r,
                        (a, b) -> a.getCreatedAt().isAfter(b.getCreatedAt()) ? a : b));
    }

    // 1 = fraud, 0 = legitimate, null = not a verdict
    private static Integer labelOf(ReviewAction action) {
        return switch (action) {
            case CONFIRM_FRAUD -> 1;
            case MARK_LEGITIMATE -> 0;
            case REQUEST_FURTHER_REVIEW -> null;
        };
    }

    private LabeledTransactionDTO toDto(Transaction t, AnalystReview review) {
        return new LabeledTransactionDTO(
                t.getId(),
                labelOf(review.getAction()),
                review.getAction(),
                review.getReviewer(),
                review.getCreatedAt(),
                t.getOccurredAt(),
                t.getAmount(),
                t.getStatus(),
                t.getRiskScore(),
                t.getAnomalyScore(),
                t.getModelVersion(),
                parseSnapshot(t.getDecisionInputs()));
    }

    private JsonNode parseSnapshot(String json) {
        if (json == null || json.isBlank()) return null;
        try {
            return objectMapper.readTree(json);
        } catch (JsonProcessingException e) {
            return null;     // corrupt snapshot: skip this row instead of failing the whole export
        }
    }
}