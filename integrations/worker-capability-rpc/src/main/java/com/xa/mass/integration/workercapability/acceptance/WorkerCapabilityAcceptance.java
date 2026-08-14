package com.xa.mass.integration.workercapability.acceptance;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorkerCapabilityAcceptance {

    private static final int RESULTS_PER_CASE = 10;
    private static final int WORKERS_PER_GROUP = 10;
    private static final String PHONE_GROUP =
            "scenario-phone-number-workers";
    private static final String STRING_GROUP =
            "scenario-string-utils-workers";
    private static final Map<String, ExpectedCase> EXPECTED = expected();

    private WorkerCapabilityAcceptance() {
    }

    public static void verify(
            List<Map<String, Object>> results,
            Path scenarioWorkerLabRoot
    ) throws IOException {
        if (results.size() != EXPECTED.size() * RESULTS_PER_CASE) {
            throw invalid("expected 60 RPC results");
        }
        Map<String, Integer> eventCounts = new HashMap<>();
        Set<String> messageIds = new HashSet<>();
        for (Map<String, Object> result : results) {
            String eventCode = requiredString(result, "eventCode");
            ExpectedCase expected = EXPECTED.get(eventCode);
            if (expected == null) {
                throw invalid("unexpected eventCode " + eventCode);
            }
            if (!expected.workerGroupId().equals(
                    requiredString(result, "workerGroupId")
            )) {
                throw invalid("unexpected WorkerGroup for " + eventCode);
            }
            String messageId = requiredString(result, "messageId");
            if (!messageIds.add(messageId)) {
                throw invalid("messageId values must be globally unique");
            }
            Map<String, Object> input = requiredMap(result, "input");
            Object inputValue = input.get(expected.inputField());
            if (!(inputValue instanceof String)) {
                throw invalid(eventCode + " requires input field "
                        + expected.inputField());
            }
            Map<String, Object> payload = requiredMap(result, "result");
            if (!Boolean.TRUE.equals(payload.get("valid"))
                    || !payload.containsKey(expected.resultField())) {
                throw invalid(eventCode + " result is invalid");
            }
            eventCounts.merge(eventCode, 1, Integer::sum);
        }
        EXPECTED.keySet().forEach(eventCode -> {
            if (eventCounts.getOrDefault(eventCode, 0)
                    != RESULTS_PER_CASE) {
                throw invalid(eventCode + " expected 10 results");
            }
        });
        verifyPersistentWorkerIdentities(scenarioWorkerLabRoot);
    }

    private static void verifyPersistentWorkerIdentities(
            Path scenarioWorkerLabRoot
    ) throws IOException {
        Set<String> allWorkerIds = new HashSet<>();
        for (String workerGroupId : List.of(PHONE_GROUP, STRING_GROUP)) {
            Path groupDirectory = scenarioWorkerLabRoot.resolve(
                    workerGroupId
            );
            List<Path> workerFiles;
            try (var files = Files.list(groupDirectory)) {
                workerFiles = files
                        .filter(Files::isRegularFile)
                        .filter(path -> path.getFileName().toString()
                                .endsWith(".json"))
                        .sorted()
                        .toList();
            }
            if (workerFiles.size() != WORKERS_PER_GROUP) {
                throw invalid(
                        workerGroupId + " must contain 10 persistent Workers"
                );
            }
            Set<String> groupWorkerIds = new HashSet<>();
            for (Path workerFile : workerFiles) {
                Map<String, Object> state = Jsons.parseObject(
                        Files.readString(
                                workerFile,
                                StandardCharsets.UTF_8
                        )
                );
                Object schemaVersion = state.get("schemaVersion");
                if (!(schemaVersion instanceof Number)
                        || ((Number) schemaVersion).intValue() != 1) {
                    throw invalid(
                            workerFile + " has an invalid schemaVersion"
                    );
                }
                String workerId = requireCanonicalWorkerId(
                        state,
                        workerFile
                );
                if (!groupWorkerIds.add(workerId)
                        || !allWorkerIds.add(workerId)) {
                    throw invalid("Worker IDs must be globally unique");
                }
            }
        }
        if (allWorkerIds.size() != 20) {
            throw invalid("expected 20 persistent Worker IDs");
        }
    }

    private static String requiredString(
            Map<String, Object> values,
            String name
    ) {
        Object value = values.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid("result requires " + name);
        }
        return text;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requiredMap(
            Map<String, Object> values,
            String name
    ) {
        Object value = values.get(name);
        if (!(value instanceof Map<?, ?>)) {
            throw invalid("result requires " + name);
        }
        return (Map<String, Object>) value;
    }

    private static String requireCanonicalWorkerId(
            Map<String, Object> state,
            Path owner
    ) {
        Object raw = state.get("workerId");
        if (!(raw instanceof String workerId) || workerId.isBlank()) {
            throw invalid(owner + " has an invalid workerId");
        }
        try {
            if (!UUID.fromString(workerId).toString().equals(workerId)) {
                throw invalid(owner + " has a non-canonical workerId");
            }
        } catch (IllegalArgumentException error) {
            throw invalid(owner + " has an invalid workerId", error);
        }
        return workerId;
    }

    private static Map<String, ExpectedCase> expected() {
        Map<String, ExpectedCase> values = new LinkedHashMap<>();
        values.put(
                "phonenumber.e164",
                new ExpectedCase(PHONE_GROUP, "rawNumber", "e164")
        );
        values.put(
                "phonenumber.country",
                new ExpectedCase(
                        PHONE_GROUP,
                        "rawNumber",
                        "countryCallingCode"
                )
        );
        values.put(
                "phonenumber.original-carrier",
                new ExpectedCase(
                        PHONE_GROUP,
                        "rawNumber",
                        "originalCarrier"
                )
        );
        values.put(
                "string.md5",
                new ExpectedCase(STRING_GROUP, "value", "md5")
        );
        values.put(
                "string.sha1",
                new ExpectedCase(STRING_GROUP, "value", "sha1")
        );
        values.put(
                "string.base64.encode",
                new ExpectedCase(STRING_GROUP, "value", "base64")
        );
        return Map.copyOf(values);
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Task Batch proof is invalid: " + message
        );
    }

    private static IllegalStateException invalid(
            String message,
            Throwable cause
    ) {
        return new IllegalStateException(
                "Task Batch proof is invalid: " + message,
                cause
        );
    }

    private record ExpectedCase(
            String workerGroupId,
            String inputField,
            String resultField
    ) {
    }
}
