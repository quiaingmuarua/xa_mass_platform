package com.xa.mass.integration.workercapability;

import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

final class WorkerCapabilityScenarioVerifier {

    private static final List<FileContract> CONTRACTS = List.of(
            new FileContract(
                    "phone-number.jsonl",
                    "scenario-phone-number-workers",
                    "scenario-phone-number-worker-",
                    Set.of(
                            "phonenumber.e164",
                            "phonenumber.country",
                            "phonenumber.original-carrier"
                    )
            ),
            new FileContract(
                    "string-utils.jsonl",
                    "scenario-string-utils-workers",
                    "scenario-string-utils-worker-",
                    Set.of(
                            "string.md5",
                            "string.sha1",
                            "string.base64.encode"
                    )
            )
    );

    private WorkerCapabilityScenarioVerifier() {
    }

    static void verify(Path scenarioResultDirectory) throws IOException {
        Set<String> allWorkerIds = new HashSet<>();
        int totalResults = 0;
        for (FileContract contract : CONTRACTS) {
            FileProof proof = verifyFile(
                    scenarioResultDirectory.resolve(contract.filename()),
                    contract
            );
            allWorkerIds.addAll(proof.workerIds());
            totalResults += proof.resultCount();
        }
        if (totalResults != 60) {
            throw invalid(
                    "expected 60 results but received " + totalResults
            );
        }
        if (allWorkerIds.size() != 20) {
            throw invalid(
                    "Worker IDs must be unique across both WorkerGroups"
            );
        }
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

        Set<String> expectedWorkerKeys = expectedWorkerKeys(contract);
        Map<String, String> workerIdsByKey = new LinkedHashMap<>();
        Map<WorkerEvent, Integer> combinations = new HashMap<>();
        for (Map<String, Object> row : rows) {
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

            String clientWorkerKey = requireString(
                    row,
                    "clientWorkerKey",
                    contract.filename()
            );
            if (!expectedWorkerKeys.contains(clientWorkerKey)) {
                throw invalid(
                        contract.filename()
                                + " contains unexpected Worker key "
                                + clientWorkerKey
                );
            }
            String workerId = requireCanonicalWorkerId(
                    row,
                    contract.filename()
            );
            String existing = workerIdsByKey.putIfAbsent(
                    clientWorkerKey,
                    workerId
            );
            if (existing != null && !existing.equals(workerId)) {
                throw invalid(
                        contract.filename()
                                + " changed Worker ID for "
                                + clientWorkerKey
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
            if (!row.containsKey("input") || !row.containsKey("result")) {
                throw invalid(
                        contract.filename() + " contains an incomplete result"
                );
            }
            combinations.merge(
                    new WorkerEvent(clientWorkerKey, eventCode),
                    1,
                    Integer::sum
            );
        }

        if (!workerIdsByKey.keySet().equals(expectedWorkerKeys)) {
            throw invalid(
                    contract.filename()
                            + " does not contain the configured Worker keys"
            );
        }
        Set<String> workerIds = Set.copyOf(workerIdsByKey.values());
        if (workerIds.size() != 10) {
            throw invalid(
                    contract.filename()
                            + " must contain 10 distinct Worker IDs"
            );
        }
        if (!combinations.equals(expectedCombinations(
                expectedWorkerKeys,
                contract.eventCodes()
        ))) {
            throw invalid(
                    contract.filename()
                            + " has incomplete Worker/event coverage"
            );
        }
        return new FileProof(rows.size(), workerIds);
    }

    private static List<Map<String, Object>> readRows(Path path)
            throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (String line : Files.readAllLines(
                path,
                StandardCharsets.UTF_8
        )) {
            if (!line.isBlank()) {
                rows.add(Jsons.parseObject(line));
            }
        }
        return List.copyOf(rows);
    }

    private static Set<String> expectedWorkerKeys(FileContract contract) {
        Set<String> workerKeys = new HashSet<>();
        for (int index = 1; index <= 10; index++) {
            workerKeys.add(
                    contract.workerKeyPrefix() + "%03d".formatted(index)
            );
        }
        return Set.copyOf(workerKeys);
    }

    private static Map<WorkerEvent, Integer> expectedCombinations(
            Set<String> workerKeys,
            Set<String> eventCodes
    ) {
        Map<WorkerEvent, Integer> combinations = new HashMap<>();
        for (String workerKey : workerKeys) {
            for (String eventCode : eventCodes) {
                combinations.put(
                        new WorkerEvent(workerKey, eventCode),
                        1
                );
            }
        }
        return Map.copyOf(combinations);
    }

    private static String requireCanonicalWorkerId(
            Map<String, Object> row,
            String filename
    ) {
        String workerId = requireString(row, "workerId", filename);
        try {
            if (!UUID.fromString(workerId).toString().equals(workerId)) {
                throw invalid(
                        filename + " contains non-canonical Worker ID"
                );
            }
        } catch (IllegalArgumentException error) {
            throw invalid(
                    filename + " contains invalid Worker ID " + workerId,
                    error
            );
        }
        return workerId;
    }

    private static String requireString(
            Map<String, Object> row,
            String field,
            String filename
    ) {
        Object value = row.get(field);
        if (!(value instanceof String text) || text.isBlank()) {
            throw invalid(
                    filename + " contains invalid field " + field
            );
        }
        return text;
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
            String workerKeyPrefix,
            Set<String> eventCodes
    ) {
    }

    private record WorkerEvent(
            String clientWorkerKey,
            String eventCode
    ) {
    }

    private record FileProof(
            int resultCount,
            Set<String> workerIds
    ) {
    }
}
