package com.fraudguard.entity;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fraudguard.dto.RuleHit;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;
import java.util.List;

// JPA calls this automatically for any field marked @Convert(converter = RuleHitsConverter.class):
//   saving:  List<RuleHit> -> JSON text in the rule_hits column
//   loading: JSON text     -> List<RuleHit>
@Converter
public class RuleHitsConverter implements AttributeConverter<List<RuleHit>, String> {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<RuleHit>> HIT_LIST = new TypeReference<>() {
    };

    @Override
    public String convertToDatabaseColumn(List<RuleHit> hits) {
        if (hits == null) {
            return null;
        }
        try {
            return MAPPER.writeValueAsString(hits);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not store rule hits", e);
        }
    }

    @Override
    public List<RuleHit> convertToEntityAttribute(String json) {
        if (json == null || json.isBlank()) {
            return List.of();   // not processed yet (or no rule fired): an empty list, never null
        }
        try {
            return MAPPER.readValue(json, HIT_LIST);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Could not read stored rule hits", e);
        }
    }
}