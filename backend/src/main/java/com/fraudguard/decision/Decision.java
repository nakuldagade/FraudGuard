package com.fraudguard.decision;

import com.fraudguard.entity.TransactionStatus;
import java.util.List;

// The final decision for one transaction.
public record Decision(
        TransactionStatus status,       // APPROVED / REVIEW / BLOCKED
        List<String> reasonCodes,       // machine-readable reasons, e.g. [HIGH_VELOCITY, ML_ANOMALY_VERY_HIGH]
        String explanation,             // the same in plain English, with the actual numbers
        String policyVersion,
        DecisionInputs inputs           // full snapshot for later analysis
) {
}