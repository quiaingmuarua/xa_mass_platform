package com.xa.mass.integration.workercapability.acceptance;

import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance.ExpectedItem;
import com.xa.mass.integration.workercapability.acceptance.WorkerCapabilityAcceptance.ObservedResult;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class CapabilityTaskEvidence {

    private CapabilityTaskEvidence() {
    }

    public static void writeSucceeded(
            Path path,
            String proofId,
            List<TaskSummary> tasks,
            WorkerCapabilityAcceptance.Summary summary
    ) throws IOException {
        write(path, evidence(
                proofId,
                "succeeded",
                tasks,
                summary.eventResultCounts(),
                summary.messageIds(),
                List.of()
        ));
    }

    public static void writeFailed(
            Path path,
            String proofId,
            List<TaskSummary> tasks,
            Map<String, ExpectedItem> manifest,
            List<ObservedResult> results,
            RuntimeException failure
    ) throws IOException {
        Map<String, Integer> eventCounts = new LinkedHashMap<>();
        Set<String> messageIds = new LinkedHashSet<>();
        for (ObservedResult result : results) {
            ExpectedItem expected = manifest.get(result.messageId());
            if (expected != null) {
                eventCounts.merge(expected.combination(), 1, Integer::sum);
            }
            messageIds.add(result.messageId());
        }
        write(path, evidence(
                proofId,
                "failed",
                tasks,
                eventCounts,
                messageIds.stream().sorted().toList(),
                List.of(failure.getClass().getSimpleName())
        ));
    }

    private static Map<String, Object> evidence(
            String proofId,
            String status,
            List<TaskSummary> tasks,
            Map<String, Integer> eventCounts,
            List<String> messageIds,
            List<String> failures
    ) {
        List<Map<String, Object>> encodedTasks = new ArrayList<>();
        int itemCount = 0;
        int resultCount = 0;
        for (TaskSummary task : tasks) {
            Map<String, Object> encoded = new LinkedHashMap<>();
            encoded.put("taskId", task.taskId());
            encoded.put("workerGroupId", task.workerGroupId());
            encoded.put("itemCount", task.itemCount());
            encoded.put("resultCount", task.resultCount());
            encodedTasks.add(encoded);
            itemCount += task.itemCount();
            resultCount += task.resultCount();
        }
        Map<String, Object> evidence = new LinkedHashMap<>();
        evidence.put("schemaVersion", 1);
        evidence.put("proofId", proofId);
        evidence.put("status", status);
        evidence.put("taskCount", tasks.size());
        evidence.put("itemCount", itemCount);
        evidence.put("resultCount", resultCount);
        evidence.put("eventResultCounts", eventCounts);
        evidence.put("messageIds", messageIds);
        evidence.put("tasks", encodedTasks);
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

    public record TaskSummary(
            String taskId,
            String workerGroupId,
            int itemCount,
            int resultCount
    ) {
    }
}
