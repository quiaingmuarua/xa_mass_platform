package com.xa.mass.integration.workercapability.acceptance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkerCapabilityAcceptance {

    private static final int RESULTS_PER_CASE = 10;
    private static final Map<String, String> GROUP_BY_EVENT = expected();

    private WorkerCapabilityAcceptance() {
    }

    public static Summary verify(List<Map<String, Object>> results) {
        if (results.size() != GROUP_BY_EVENT.size() * RESULTS_PER_CASE) {
            throw invalid("expected 60 RPC results");
        }
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        Set<String> messageIds = new LinkedHashSet<>();
        for (Map<String, Object> result : results) {
            String eventCode = requiredString(result, "eventCode");
            String expectedGroup = GROUP_BY_EVENT.get(eventCode);
            if (expectedGroup == null) {
                throw invalid("unexpected eventCode " + eventCode);
            }
            if (!expectedGroup.equals(requiredString(
                    result,
                    "workerGroupId"
            ))) {
                throw invalid("unexpected WorkerGroup for " + eventCode);
            }
            String messageId = requiredString(result, "messageId");
            if (!messageIds.add(messageId)) {
                throw invalid("messageId values must be globally unique");
            }
            requiredMap(result, "input");
            requiredMap(result, "result");
            eventCounts.merge(eventCode, 1, Integer::sum);
        }
        GROUP_BY_EVENT.keySet().forEach(eventCode -> {
            if (eventCounts.getOrDefault(eventCode, 0)
                    != RESULTS_PER_CASE) {
                throw invalid(eventCode + " expected 10 results");
            }
        });
        Map<String, Integer> orderedCounts = new LinkedHashMap<>();
        GROUP_BY_EVENT.keySet().forEach(eventCode ->
                orderedCounts.put(eventCode, eventCounts.get(eventCode)));
        return new Summary(
                Collections.unmodifiableMap(
                        orderedCounts
                ),
                messageIds.stream().sorted().toList()
        );
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

    private static Map<?, ?> requiredMap(
            Map<String, Object> values,
            String name
    ) {
        Object value = values.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw invalid("result requires " + name);
        }
        return map;
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Worker capability acceptance failed: " + message
        );
    }

    private static Map<String, String> expected() {
        Map<String, String> expected = new LinkedHashMap<>();
        expected.put(
                "extension.worker.phonenumber.e164",
                "scenario-phone-number-workers"
        );
        expected.put(
                "extension.worker.phonenumber.country",
                "scenario-phone-number-workers"
        );
        expected.put(
                "extension.worker.phonenumber.original-carrier",
                "scenario-phone-number-workers"
        );
        expected.put(
                "extension.worker.string.md5",
                "scenario-string-utils-workers"
        );
        expected.put(
                "extension.worker.string.sha1",
                "scenario-string-utils-workers"
        );
        expected.put(
                "extension.worker.string.base64.encode",
                "scenario-string-utils-workers"
        );
        return Collections.unmodifiableMap(expected);
    }

    static Map<String, Integer> expectedResultCounts() {
        Map<String, Integer> counts = new LinkedHashMap<>();
        GROUP_BY_EVENT.keySet().forEach(eventCode ->
                counts.put(eventCode, RESULTS_PER_CASE));
        return Collections.unmodifiableMap(counts);
    }

    public record Summary(
            Map<String, Integer> eventResultCounts,
            List<String> messageIds
    ) {

        public Summary {
            eventResultCounts = Collections.unmodifiableMap(
                    new LinkedHashMap<>(eventResultCounts)
            );
            messageIds = List.copyOf(messageIds);
        }
    }
}
