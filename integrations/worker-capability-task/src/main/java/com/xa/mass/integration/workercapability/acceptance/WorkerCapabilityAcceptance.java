package com.xa.mass.integration.workercapability.acceptance;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkerCapabilityAcceptance {

    private static final int EXPECTED_RESULT_COUNT = 60;
    private static final int RESULTS_PER_EVENT = 10;

    private WorkerCapabilityAcceptance() {
    }

    public static Summary verify(
            Map<String, ExpectedItem> manifest,
            List<ObservedResult> results
    ) {
        if (manifest.size() != EXPECTED_RESULT_COUNT) {
            throw invalid("expected 60 manifest Items");
        }
        if (results.size() != EXPECTED_RESULT_COUNT) {
            throw invalid("expected 60 exported success results");
        }
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        Set<String> messageIds = new LinkedHashSet<>();
        for (ObservedResult result : results) {
            ExpectedItem expected = manifest.get(result.messageId());
            if (expected == null) {
                throw invalid(
                        "unexpected messageId " + result.messageId()
                );
            }
            if (!messageIds.add(result.messageId())) {
                throw invalid("messageId values must be globally unique");
            }
            if (result.opaqueResultPayload().isBlank()) {
                throw invalid("success Result payload must be non-empty");
            }
            eventCounts.merge(expected.combination(), 1, Integer::sum);
        }
        if (!messageIds.equals(manifest.keySet())) {
            throw invalid("exported messageIds do not match the manifest");
        }
        Map<String, Integer> orderedCounts = new LinkedHashMap<>();
        manifest.values().forEach(item ->
                orderedCounts.putIfAbsent(item.combination(), 0));
        orderedCounts.keySet().forEach(combination -> {
            int count = eventCounts.getOrDefault(combination, 0);
            if (count != RESULTS_PER_EVENT) {
                throw invalid(combination + " expected 10 results");
            }
            orderedCounts.put(combination, count);
        });
        if (orderedCounts.size() != 6) {
            throw invalid("expected six WorkerGroup/Event combinations");
        }
        return new Summary(
                Collections.unmodifiableMap(orderedCounts),
                messageIds.stream().sorted().toList()
        );
    }

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Worker capability acceptance failed: " + message
        );
    }

    public record ExpectedItem(
            String workerGroupId,
            String eventCode
    ) {
        public String combination() {
            return workerGroupId + "/" + eventCode;
        }
    }

    public record ObservedResult(
            String messageId,
            String opaqueResultPayload
    ) {
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
