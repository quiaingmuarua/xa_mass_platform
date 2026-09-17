package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.Map;
import java.util.Objects;

/**
 * Fixed construction-time, thread-safe strategy functions. Neither owns an Item or a Task.
 * All state and candidate selection must be isolated by the supplied Group.
 */
public record QueryFunctions(Normalizer normalizeInput, Executor execute) {
    public QueryFunctions {
        Objects.requireNonNull(normalizeInput, "normalizeInput");
        Objects.requireNonNull(execute, "execute");
    }

    @FunctionalInterface
    public interface Normalizer {
        /** Idempotent local admission without Redis reads or stock changes. Returns a JSON value. */
        Object apply(String workerGroupId, Object input);
    }

    @FunctionalInterface
    public interface Executor {
        /** At most 100 admitted inputs, in request order. Missing identities remain unassigned. */
        Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId);
    }
}
