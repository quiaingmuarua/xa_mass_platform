package com.xa.mass.integration.workercapability.scenario;

import com.xa.mass.integration.workercapability.process.RpcResult;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class WorkerCapabilityAcceptance {

    private static final int INPUTS_PER_PROCESS = 10;
    private static final int WORKERS_PER_GROUP = 10;

    private WorkerCapabilityAcceptance() {
    }

    public static void verify(
            List<RpcResult> phoneResults,
            List<RpcResult> stringResults,
            Path scenarioWorkerLabRoot
    ) throws IOException {
        Set<String> allMessageIds = new HashSet<>();
        verifyResults(
                phoneResults,
                PhoneNumberProcess.WORKER_GROUP_ID,
                PhoneNumberProcess.EVENT_CODES,
                allMessageIds
        );
        verifyResults(
                stringResults,
                StringUtilityProcess.WORKER_GROUP_ID,
                StringUtilityProcess.EVENT_CODES,
                allMessageIds
        );
        if (phoneResults.size() + stringResults.size() != 60) {
            throw invalid("expected 60 RPC results");
        }
        verifyPersistentWorkerIdentities(scenarioWorkerLabRoot);
    }

    private static void verifyResults(
            List<RpcResult> results,
            String expectedWorkerGroupId,
            List<String> expectedEventCodes,
            Set<String> allMessageIds
    ) {
        int expectedCount = INPUTS_PER_PROCESS
                * expectedEventCodes.size();
        if (results.size() != expectedCount) {
            throw invalid(
                    expectedWorkerGroupId
                            + " expected "
                            + expectedCount
                            + " results but received "
                            + results.size()
            );
        }
        Map<String, Integer> eventCounts = new HashMap<>();
        for (RpcResult result : results) {
            if (!expectedWorkerGroupId.equals(result.workerGroupId())) {
                throw invalid(
                        "unexpected WorkerGroup " + result.workerGroupId()
                );
            }
            if (!expectedEventCodes.contains(result.eventCode())) {
                throw invalid("unexpected eventCode " + result.eventCode());
            }
            if (!allMessageIds.add(result.messageId())) {
                throw invalid(
                        "messageId values must be globally unique: "
                                + result.messageId()
                );
            }
            eventCounts.merge(result.eventCode(), 1, Integer::sum);
        }
        for (String eventCode : expectedEventCodes) {
            if (eventCounts.getOrDefault(eventCode, 0)
                    != INPUTS_PER_PROCESS) {
                throw invalid(
                        expectedWorkerGroupId
                                + " must contain "
                                + INPUTS_PER_PROCESS
                                + " results for "
                                + eventCode
                );
            }
        }
    }

    private static void verifyPersistentWorkerIdentities(
            Path scenarioWorkerLabRoot
    ) throws IOException {
        Set<String> allWorkerIds = new HashSet<>();
        for (String workerGroupId : List.of(
                PhoneNumberProcess.WORKER_GROUP_ID,
                StringUtilityProcess.WORKER_GROUP_ID
        )) {
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
                        workerGroupId
                                + " must contain "
                                + WORKERS_PER_GROUP
                                + " persistent Worker files"
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
                if (!groupWorkerIds.add(workerId)) {
                    throw invalid(
                            workerGroupId + " contains duplicate Worker IDs"
                    );
                }
                if (!allWorkerIds.add(workerId)) {
                    throw invalid(
                            "Worker IDs must be unique across WorkerGroups"
                    );
                }
            }
        }
        if (allWorkerIds.size() != 20) {
            throw invalid("expected 20 persistent Worker IDs");
        }
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

    private static IllegalStateException invalid(String message) {
        return new IllegalStateException(
                "Scenario RPC proof is invalid: " + message
        );
    }

    private static IllegalStateException invalid(
            String message,
            Throwable cause
    ) {
        return new IllegalStateException(
                "Scenario RPC proof is invalid: " + message,
                cause
        );
    }
}
