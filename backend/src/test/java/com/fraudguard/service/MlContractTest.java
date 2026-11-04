package com.fraudguard.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.dto.MlScoreRequest;
import com.fraudguard.dto.MlScoreResponse;
import com.networknt.schema.JsonSchema;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashSet;
import java.util.Iterator;
import java.util.Set;
import org.junit.jupiter.api.Test;

// The Java side of contracts/ml-score.schema.json (the Python side is ml-service/tests/test_contract.py).
// No Spring, no Docker.
class MlContractTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final JsonNode CONTRACT = load();

    private static JsonNode load() {
        try {
            return MAPPER.readTree(Files.readString(Path.of("..", "contracts", "ml-score.schema.json")));
        } catch (Exception e) {
            throw new IllegalStateException("cannot read contracts/ml-score.schema.json (run tests from backend/)", e);
        }
    }

    private static JsonSchema schema(String name) throws Exception {
        // A tiny wrapper schema that points at one definition inside the contract
        JsonNode wrapper = MAPPER.readTree("{\"$defs\":" + CONTRACT.get("$defs") + ",\"$ref\":\"#/$defs/" + name + "\"}");
        return JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V202012).getSchema(wrapper);
    }

    private static Set<String> names(JsonNode object) {
        Set<String> out = new HashSet<>();
        for (Iterator<String> it = object.fieldNames(); it.hasNext(); ) {
            out.add(it.next());
        }
        return out;
    }

    private static MlScoreRequest request(Double zScore, Long secondsSincePrevious) {
        return new MlScoreRequest("t-1", 5000.0, 14, 7, 7, 6, 98.5, zScore, secondsSincePrevious, true, true, true);
    }

    @Test
    void serialisedRequestValidatesAgainstTheContract() throws Exception {
        JsonNode json = MAPPER.valueToTree(request(2619.96, 5L));

        assertThat(schema("ScoreRequest").validate(json)).isEmpty();
    }

    @Test
    void requestWithNoHistoryValidatesBecauseNullsAreAllowed() throws Exception {
        JsonNode json = MAPPER.valueToTree(request(null, null));

        assertThat(json.get("amount_z_score").isNull()).isTrue();   // sent as an explicit null, not omitted
        assertThat(schema("ScoreRequest").validate(json)).isEmpty();
    }

    @Test
    void requestFieldNamesMatchTheContractExactly() {
        Set<String> sent = names(MAPPER.valueToTree(request(1.0, 1L)));

        assertThat(sent).isEqualTo(names(CONTRACT.at("/$defs/ScoreRequest/properties")));
    }

    @Test
    void contractExamplesAreValidAndTheResponseExampleParsesIntoTheJavaRecord() throws Exception {
        assertThat(schema("ScoreRequest").validate(CONTRACT.at("/examples/request"))).isEmpty();
        assertThat(schema("ScoreRequest").validate(CONTRACT.at("/examples/requestFirstEverTransaction"))).isEmpty();
        assertThat(schema("ScoreResponse").validate(CONTRACT.at("/examples/response"))).isEmpty();

        MlScoreResponse parsed = MAPPER.treeToValue(CONTRACT.at("/examples/response"), MlScoreResponse.class);
        assertThat(parsed.transactionId()).isEqualTo("t-1");
        assertThat(parsed.anomalyScore()).isEqualTo(0.81);
        assertThat(parsed.modelVersion()).isEqualTo("isoforest-v1");
        assertThat(parsed.featureSchemaVersion()).isEqualTo("1");
    }

    @Test
    void responseWithExtraFieldsStillParsesSoPythonCanAddFieldsLater() throws Exception {
        JsonNode withExtra = MAPPER.readTree(
                "{\"transaction_id\":\"t\",\"anomaly_score\":0.2,\"model_version\":\"m\",\"feature_schema_version\":\"1\",\"new_field\":1}");

        assertThat(schema("ScoreResponse").validate(withExtra)).isEmpty();
        assertThat(MAPPER.treeToValue(withExtra, MlScoreResponse.class).anomalyScore()).isEqualTo(0.2);
    }
}
