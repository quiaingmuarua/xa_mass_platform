package com.xa.mass.integration.workercapability.acceptance;

import com.xa.mass.integration.workercapability.runtimeapi.TaskBatchApiClient.RunResult;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class TaskBatchEvidence {

    private TaskBatchEvidence() {
    }

    public static void writeSucceeded(
            Path path,
            String proofId,
            List<RunResult> runs,
            WorkerCapabilityAcceptance.Summary summary
    ) throws IOException {
        write(path, evidence(
                proofId,
                "succeeded",
                runs,
                summary.eventResultCounts(),
                summary.messageIds(),
                Map.of(),
                List.of(),
                List.of()
        ));
    }

    public static void writeFailed(
            Path path,
            String proofId,
            List<RunResult> runs,
            List<Map<String, Object>> results,
            RuntimeException failure
    ) throws IOException {
        Observation observation = observe(results);
        write(path, evidence(
                proofId,
                "failed",
                runs,
                observation.eventResultCounts(),
                observation.messageIds(),
                observation.missingResultCounts(),
                observation.duplicateMessageIds(),
                List.of(safeMessage(failure))
        ));
    }

    private static Map<String, Object> evidence(
            String proofId,
            String status,
            List<RunResult> runs,
            Map<String, Integer> eventCounts,
            List<String> messageIds,
            Map<String, Integer> missingResultCounts,
            List<String> duplicateMessageIds,
            List<String> failures
    ) {
        List<Map<String, Object>> encodedRuns = new ArrayList<>();
        int inputCount = 0;
        int resultCount = 0;
        int remainingCount = 0;
        for (RunResult run : runs) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("runId", run.runId());
            encoded.put("workerGroupId", run.workerGroupId());
            encoded.put("eventCode", run.eventCode());
            encoded.put("status", run.status());
            encoded.put("inputCount", run.inputCount());
            encoded.put("resultCount", run.resultCount());
            encoded.put("remainingCount", run.remainingCount());
            encoded.put("loadRounds", run.loadRounds());
            encodedRuns.add(encoded);
            inputCount += run.inputCount();
            resultCount += run.resultCount();
            remainingCount += run.remainingCount();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("proofId", proofId);
        evidence.put("status", status);
        evidence.put("batchCount", runs.size());
        evidence.put("inputCount", inputCount);
        evidence.put("resultCount", resultCount);
        evidence.put("remainingCount", remainingCount);
        evidence.put("eventResultCounts", eventCounts);
        evidence.put("messageIds", messageIds);
        evidence.put("missingResultCounts", missingResultCounts);
        evidence.put("duplicateMessageIds", duplicateMessageIds);
        evidence.put("runs", encodedRuns);
        evidence.put("failures", failures);
        return evidence;
    }

    private static void write(Path path, Map<String, Object> evidence)
            throws IOException {
        Path parent = path.getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.writeString(
                path,
                Jsons.toJson(evidence),
                StandardCharsets.UTF_8,
                StandardOpenOption.CREATE_NEW,
                StandardOpenOption.WRITE
        );
    }

    private static String safeMessage(RuntimeException failure) {
        return failure.getClass().getSimpleName();
    }

    private static Observation observe(List<Map<String, Object>> results) {
        Map<String, Integer> expected =
                WorkerCapabilityAcceptance.expectedResultCounts();
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        Set<String> messageIds = new LinkedHashSet<>();
        Set<String> duplicates = new LinkedHashSet<>();
        for (Map<String, Object> result : results) {
            Object rawEvent = result.get("eventCode");
            if (rawEvent instanceof String eventCode
                    && expected.containsKey(eventCode)) {
                eventCounts.merge(eventCode, 1, Integer::sum);
            }
            Object rawMessageId = result.get("messageId");
            if (rawMessageId instanceof String messageId
                    && !messageId.isBlank()
                    && !messageIds.add(messageId)) {
                duplicates.add(messageId);
            }
        }
        Map<String, Integer> missing = new LinkedHashMap<>();
        Map<String, Integer> orderedCounts = new LinkedHashMap<>();
        expected.forEach((eventCode, count) -> {
            int observed = eventCounts.getOrDefault(eventCode, 0);
            if (observed > 0) {
                orderedCounts.put(eventCode, observed);
            }
            if (observed < count) {
                missing.put(eventCode, count - observed);
            }
        });
        return new Observation(
                Collections.unmodifiableMap(
                        orderedCounts
                ),
                messageIds.stream().sorted().toList(),
                Collections.unmodifiableMap(
                        new LinkedHashMap<>(missing)
                ),
                duplicates.stream().sorted().toList()
        );
    }

    private record Observation(
            Map<String, Integer> eventResultCounts,
            List<String> messageIds,
            Map<String, Integer> missingResultCounts,
            List<String> duplicateMessageIds
    ) {
    }
}
