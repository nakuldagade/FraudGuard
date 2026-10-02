package com.fraudguard.rules;

import com.fraudguard.dto.RuleHit;
import com.fraudguard.entity.TransactionStatus;
import java.util.List;

// The rule engine's verdict for one transaction.
public record RiskAssessment(
        int score,               // 0-100: the sum of all rule scores, capped at 100
        TransactionStatus status,   // APPROVED / REVIEW / BLOCKED
        List<RuleHit> hits       // the rules that fired, highest score first
) {
}