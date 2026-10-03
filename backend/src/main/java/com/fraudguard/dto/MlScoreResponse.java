package com.fraudguard.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;

// The ML service's answer. Unknown extra fields are ignored, so the Python side can add
// fields later without breaking this client.
@JsonIgnoreProperties(ignoreUnknown = true)
public record MlScoreResponse(
        @JsonProperty("transaction_id") String transactionId,
        @JsonProperty("anomaly_score") Double anomalyScore,       // higher = more unusual; NOT a probability
        @JsonProperty("model_version") String modelVersion,
        @JsonProperty("feature_schema_version") String featureSchemaVersion
) {
}