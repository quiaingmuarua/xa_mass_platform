package com.xa.mass.scenario;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import java.util.Map;

@JsonIgnoreProperties(ignoreUnknown = true)
record TaskScenarioSpec(
        String apiKey,
        Integer itemBatchSize,
        Map<String, Object> body
) {
}
