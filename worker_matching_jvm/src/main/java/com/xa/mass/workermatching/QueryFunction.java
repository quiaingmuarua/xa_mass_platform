package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.Map;

/**
 * A fixed, thread-safe query strategy over injected resources. It owns no Item or Task.
 * All state and candidate selection must be isolated by the supplied Group.
 */
public interface QueryFunction {

    /** Idempotent local admission without resource access. Returns a normalized JSON value. */
    Object normalizeInput(String workerGroupId, Object input);

    /**
     * Executes a bounded batch of normalized inputs admitted by Matching, in request order.
     * Missing identities remain unassigned. The returned map is an immutable snapshot.
     */
    Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId);
}
