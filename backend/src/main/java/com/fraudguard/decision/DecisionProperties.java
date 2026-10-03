package com.fraudguard.decision;

import lombok.Getter;
import lombok.Setter;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

// The tunable part of the decision policy, read from "fraudguard.decision" in application.yml.
// The defaults below are used when a value is missing, and by tests ("new DecisionProperties()").
@Component
@ConfigurationProperties(prefix = "fraudguard.decision")
@Getter
@Setter
public class DecisionProperties {

    // Written into every decision, so you can tell later which policy produced it.
    // Change it whenever the policy logic or its numbers change.
    private String policyVersion = "policy-v1";

    // The ML score only counts when the account has at least this many earlier transactions
    private int mlMinHistory = 5;

    // Anomaly score at or above this = "elevated": enough to send a clean transaction to REVIEW.
    // 0.66 is the best-F1 threshold from the training report (validation data).
    private double mlReviewAt = 0.66;

    // At or above this = "very high": together with a rule REVIEW it means BLOCKED.
    // 0.70 is where precision passed 90% on validation data.
    private double mlVeryHighAt = 0.70;
}