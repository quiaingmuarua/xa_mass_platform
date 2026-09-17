package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/** Caller-supplied identity hints, with no resource lookup or lease operation. */
public final class IdentityQueryFunction implements QueryFunction {

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        if (!(input instanceof String id) || id.isBlank())
            throw new IllegalArgumentException("workerId requires a nonblank string");
        return id;
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        var result = new LinkedHashMap<String, WorkerCandidate>();
        inputsByMessageId.forEach((id, input) -> result.put(id, new WorkerCandidate((String) input, 0)));
        return Collections.unmodifiableMap(result);
    }
}
