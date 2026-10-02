package com.fraudguard.rules;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.dto.TransactionEvent;
import com.fraudguard.dto.VelocityFeatures;
import com.fraudguard.entity.TransactionStatus;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

// Runs every rule against one transaction and combines the results.
@Service
@RequiredArgsConstructor
public class RuleEngine {

    static final int MAX_SCORE = 100;

    // Spring injects EVERY class that implements FraudRule: adding a rule class is enough,
    // nothing here needs to change.
    private final List<FraudRule> rules;
    private final RuleProperties properties;

    public RiskAssessment assess(TransactionEvent.Payload transaction, VelocityFeatures features) {
        List<RuleHit> hits = rules.stream()
                .map(rule -> rule.evaluate(transaction, features))
                .flatMap(Optional::stream)                       // keep only the rules that fired
                .sorted(Comparator.comparingInt(RuleHit::score).reversed()
                        .thenComparing(RuleHit::code))           // biggest contributor first
                .toList();

        int score = Math.min(MAX_SCORE, hits.stream().mapToInt(RuleHit::score).sum());
        return new RiskAssessment(score, statusFor(score), hits);
    }

    // Interim policy (Phase 7 replaces it with the full decision policy)
    private TransactionStatus statusFor(int score) {
        RuleProperties.Decision decision = properties.getDecision();
        if (score >= decision.getBlockAt()) {
            return TransactionStatus.BLOCKED;
        }
        if (score >= decision.getReviewAt()) {
            return TransactionStatus.REVIEW;
        }
        return TransactionStatus.APPROVED;
    }
}