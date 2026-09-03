package com.xa.mass.kernel.task;

import java.util.LinkedHashSet;
import java.util.List;

/** Finite Worker identity selector for ON_DEMAND TaskItems. */
public final class TaskItemWorkerSelector {

    private static final int MAX_TARGET_WORKERS = 100;
    private static final String WORKER_ID = "workerId";
    private static final String EQUALS = "$eq";
    private static final String IN = "$in";

    private TaskItemWorkerSelector() {
    }

    public static List<String> targetWorkerIds(List<?> workerSelector) {
        if (workerSelector == null) {
            throw invalid();
        }
        if (workerSelector.isEmpty()) {
            return List.of();
        }
        if (workerSelector.size() != 3
                || !WORKER_ID.equals(workerSelector.get(0))) {
            throw invalid();
        }
        Object operator = workerSelector.get(1);
        Object operand = workerSelector.get(2);
        if (EQUALS.equals(operator)) {
            return List.of(requireWorkerId(operand));
        }
        if (IN.equals(operator) && operand instanceof List<?> values) {
            return targetWorkerIdsFromList(values);
        }
        throw invalid();
    }

    private static List<String> targetWorkerIdsFromList(List<?> values) {
        if (values.isEmpty() || values.size() > MAX_TARGET_WORKERS) {
            throw invalid();
        }
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        for (Object value : values) {
            String workerId = requireWorkerId(value);
            if (!workerIds.add(workerId)) {
                throw new IllegalArgumentException(
                        "workerSelector workerIds must be unique"
                );
            }
        }
        return List.copyOf(workerIds);
    }

    private static String requireWorkerId(Object value) {
        if (!(value instanceof String workerId) || workerId.isBlank()) {
            throw invalid();
        }
        return workerId;
    }

    private static IllegalArgumentException invalid() {
        return new IllegalArgumentException(
                "workerSelector must be [], [workerId, $eq, id], or "
                        + "[workerId, $in, ids]"
        );
    }
}
