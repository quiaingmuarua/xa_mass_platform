package com.xa.mass.scenarioworkers;

import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/** Bounded Host-local execution evidence, independent of requested Worker targets. */
final class ScenarioWorkerExecutionWitnesses {
    static final String EVENT = "extension.worker.lab.execution-witness";
    static final int CAPACITY = 65_536;
    private final List<Map<String, Object>> records = new ArrayList<>();
    private boolean overflowed;

    WorkerEventDefinition<Map<String, Object>> definition(String groupId, String replicaKey) {
        return WorkerEventDefinition.extension("lab.execution-witness",
                WorkerEventParameterResolvers.jsonMap(), payload -> execute(groupId, replicaKey, payload));
    }

    private String execute(String groupId, String replicaKey, Map<String, Object> payload) {
        if (!payload.keySet().equals(Set.of("probeToken", "delayMillis"))
                || !(payload.get("probeToken") instanceof String token) || token.isBlank() || token.length() > 256
                || !(payload.get("delayMillis") instanceof Long delay) || delay < 0 || delay > 30_000) {
            throw new WorkerException(WorkerErrorCode.EVENT_INPUT_INVALID, "executionWitness.resolve",
                    "Expected probeToken and delayMillis in 0..30000", null);
        }
        long attempt = append(0, groupId, replicaKey, token, "ENTERED");
        try {
            Thread.sleep(delay);
            append(attempt, groupId, replicaKey, token, "COMPLETED");
            return "null";
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            append(attempt, groupId, replicaKey, token, "FAILED");
            throw new WorkerException(WorkerErrorCode.EVENT_EXECUTION_FAILED,
                    "executionWitness.execute", "Witness interrupted", error);
        }
    }

    private synchronized long append(long attempt, String group, String replica, String token, String state) {
        if (records.size() == CAPACITY) {
            overflowed = true;
            throw new WorkerException(WorkerErrorCode.EVENT_EXECUTION_FAILED,
                    "executionWitness.append", "Witness capacity exhausted", null);
        }
        long sequence = records.size() + 1L;
        records.add(Map.of("sequence", sequence, "attemptId", attempt == 0 ? sequence : attempt,
                "workerGroupId", group, "labWorkerKey", replica, "probeToken", token, "state", state));
        return sequence;
    }

    synchronized Map<String, Object> read(long after, int limit) {
        if (after < 0 || after > records.size() || limit < 1 || limit > 100) {
            throw new IllegalArgumentException("Invalid witness cursor or limit");
        }
        int end = Math.min(records.size(), (int) after + limit);
        return Map.of("records", List.copyOf(records.subList((int) after, end)),
                "nextCursor", (long) end, "overflowed", overflowed);
    }
}
