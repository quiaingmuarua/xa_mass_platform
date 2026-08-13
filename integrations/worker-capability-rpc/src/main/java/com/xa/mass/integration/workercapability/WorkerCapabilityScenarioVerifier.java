package com.xa.mass.integration.workercapability;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WorkerCapabilityScenarioVerifier {

    private static final Set<String> RESULT_FIELDS = Set.of(
            "workerGroupId",
            "messageId",
            "eventCode",
            "input",
            "result"
    );
    private static final List<FileContract> CONTRACTS = List.of(
            new FileContract(
                    "phone-number.jsonl",
                    "scenario-phone-number-workers",
                    Set.of(
                            "phonenumber.e164",
                            "phonenumber.country",
                            "phonenumber.original-carrier"
                    )
            ),
            new FileContract(
                    "string-utils.jsonl",
                    "scenario-string-utils-workers",
                    Set.of(
                            "string.md5",
                            "string.sha1",
                            "string.base64.encode"
                    )
            )
    );

    private WorkerCapabilityScenarioVerifier() {
    }

    static void verify(
            Path scenarioResultDirectory,
            Path scenarioWorkerLabRoot
    ) throws IOException {
        Set<String> allMessageIds = new HashSet<>();
        int totalResults = 0;
        for (FileContract contract : CONTRACTS) {
            FileProof proof = verifyFile(
                    scenarioResultDirectory.resolve(contract.filename()),
                    contract
            );
            if (!Collections.disjoint(
                    allMessageIds,
                    proof.messageIds()
            )) {
                throw invalid("messageId values must be globally unique");
            }
            allMessageIds.addAll(proof.messageIds());
            totalResults += proof.resultCount();
        }
        if (totalResults != 60) {
            throw invalid(
                    "expected 60 results but received " + totalResults
            );
        }
        verifyPersistentWorkerIdentities(scenarioWorkerLabRoot);
    }

    private static FileProof verifyFile(
            Path path,
            FileContract contract
    ) throws IOException {
        List<Map<String, Object>> rows = readRows(path);
        if (rows.size() != 30) {
            throw invalid(
                    contract.filename()
                            + " expected 30 results but received "
                            + rows.size()
            );
        }

        Map<String, Integer> eventCounts = new HashMap<>();
        Map<String, Set<String>> inputsByEvent = new HashMap<>();
        Set<String> messageIds = new HashSet<>();
        for (Map<String, Object> row : rows) {
            if (!row.keySet().equals(RESULT_FIELDS)) {
                throw invalid(
                        contract.filename()
                                + " contains an unexpected result field"
                );
            }
            String workerGroupId = requireString(
                    row,
                    "workerGroupId",
                    contract.filename()
            );
            if (!contract.workerGroupId().equals(workerGroupId)) {
                throw invalid(
                        contract.filename()
                                + " contains unexpected WorkerGroup "
                                + workerGroupId
                );
            }
            String messageId = requireString(
                    row,
                    "messageId",
                    contract.filename()
            );
            if (!messageIds.add(messageId)) {
                throw invalid(
                        contract.filename()
                                + " contains duplicate messageId "
                                + messageId
                );
            }
            String eventCode = requireString(
                    row,
                    "eventCode",
                    contract.filename()
            );
            if (!contract.eventCodes().contains(eventCode)) {
                throw invalid(
                        contract.filename()
                                + " contains unexpected eventCode "
                                + eventCode
                );
            }
            Map<String, Object> input = requireObject(
                    row,
                    "input",
                    contract.filename()
            );
            requireObject(row, "result", contract.filename());
            eventCounts.merge(eventCode, 1, Integer::sum);
            inputsByEvent.computeIfAbsent(
                    eventCode,
                    ignored -> new HashSet<>()
            ).add(Jsons.toJson(input));
        }

        for (String eventCode : contract.eventCodes()) {
            if (eventCounts.getOrDefault(eventCode, 0) != 10) {
                throw invalid(
                        contract.filename()
                                + " must contain 10 results for "
                                + eventCode
                );
            }
            if (inputsByEvent.getOrDefault(eventCode, Set.of()).size() != 10) {
                throw invalid(
                        contract.filename()
                                + " must contain 10 distinct inputs for "
                                + eventCode
                );
            }
        }
        return new FileProof(rows.size(), Set.copyOf(messageIds));
    }

    private static void verifyPersistentWorkerIdentities(
            Path scenarioWorkerLabRoot
    ) throws IOException {
        Set<String> allWorkerIds = new HashSet<>();
        for (FileContract contract : CONTRACTS) {
            Path groupDirectory = scenarioWorkerLabRoot.resolve(
                    contract.workerGroupId()
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
            if (workerFiles.size() != 10) {
                throw invalid(
                        contract.workerGroupId()
                                + " must contain 10 persistent Worker files"
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
                        workerFile.toString()
                );
                if (!groupWorkerIds.add(workerId)) {
                    throw invalid(
                            contract.workerGroupId()
                                    + " contains duplicate Worker IDs"
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

    private static List<Map<String, Object>> readRows(Path path)
            throws IOException {
        if (!Files.isRegularFile(path)) {
            throw invalid("result file is missing: " + path);
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        int lineNumber = 0;
        for (String line : Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        )) {
            lineNumber++;
            if (line.isBlank()) {
                throw invalid(path + " contains a blank line " + lineNumber);
            }
            try {
                rows.add(Jsons.parseObject(line));
            } catch (RuntimeException error) {
                throw invalid(
                        path + " contains invalid JSON at line " + lineNumber,
                        error
                );
            }
        }
        return List.copyOf(rows);
    }

    private static String requireString(
            Map<String, Object> value,
            String field,
            String owner
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof String) || ((String) raw).isBlank()) {
            throw invalid(owner + " has invalid " + field);
        }
        return (String) raw;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(
            Map<String, Object> value,
            String field,
            String owner
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof Map<?, ?>)) {
            throw invalid(owner + " has invalid " + field);
        }
        return (Map<String, Object>) raw;
    }

    private static String requireCanonicalWorkerId(
            Map<String, Object> state,
            String owner
    ) {
        String workerId = requireString(state, "workerId", owner);
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

    private record FileContract(
            String filename,
            String workerGroupId,
            Set<String> eventCodes
    ) {
    }

    private record FileProof(
            int resultCount,
            Set<String> messageIds
    ) {
    }
}
