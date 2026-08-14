package com.xa.mass.scenariorpc;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Function;

public final class ScenarioRpcEngine {

    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";

    private final List<ScenarioRpcScenario> scenarios;
    private final Map<String, ScenarioRpcScenario> scenariosByType;

    private ScenarioRpcEngine() {
        scenarios = List.of(
                phone("phonenumber.e164", "e164"),
                phone("phonenumber.country", "countryCallingCode"),
                phone(
                        "phonenumber.original-carrier",
                        "originalCarrier"
                ),
                string("string.md5", "md5"),
                string("string.sha1", "sha1"),
                string("string.base64.encode", "base64")
        );
        Map<String, ScenarioRpcScenario> indexed = new LinkedHashMap<>();
        for (ScenarioRpcScenario scenario : scenarios) {
            indexed.put(scenario.descriptor().scenarioType(), scenario);
        }
        scenariosByType = Collections.unmodifiableMap(indexed);
    }

    public static ScenarioRpcEngine create() {
        return new ScenarioRpcEngine();
    }

    public List<ScenarioRpcDescriptor> scenarioTypes() {
        return scenarios.stream()
                .map(ScenarioRpcScenario::descriptor)
                .toList();
    }

    public ScenarioRpcScenario createScenario(String scenarioType) {
        ScenarioRpcScenario scenario = scenariosByType.get(scenarioType);
        if (scenario == null) {
            throw new IllegalArgumentException(
                    "unknown scenarioType: " + scenarioType
            );
        }
        return scenario;
    }

    private static ScenarioRpcScenario phone(
            String eventCode,
            String requiredField
    ) {
        return scenario(
                eventCode,
                PHONE_GROUP,
                line -> {
                    if (line == null || line.trim().isEmpty()) {
                        throw new IllegalArgumentException(
                                "phone number line must not be blank"
                        );
                    }
                    return Map.of("rawNumber", line.trim());
                },
                requiredField
        );
    }

    private static ScenarioRpcScenario string(
            String eventCode,
            String requiredField
    ) {
        return scenario(
                eventCode,
                STRING_GROUP,
                line -> Map.of(
                        "value",
                        Objects.requireNonNull(line, "line")
                ),
                requiredField
        );
    }

    private static ScenarioRpcScenario scenario(
            String scenarioType,
            String workerGroupId,
            Function<String, Map<String, Object>> parser,
            String requiredField
    ) {
        return new ScenarioRpcScenario(
                new ScenarioRpcDescriptor(
                        scenarioType,
                        workerGroupId,
                        scenarioType
                ),
                parser,
                requiredField
        );
    }
}
