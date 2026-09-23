package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import java.util.*;
import java.util.function.BiFunction;

/** Call-local grouping and association; all rule interpretation finishes before polling. */
final class PoolCandidates {
    private PoolCandidates() { }

    static Map<String, WorkerCandidate> take(Map<String, Object> inputs,
            BiFunction<Object, Integer, List<WorkerCandidate>> poll) {
        var groups = new LinkedHashMap<Object, List<String>>();
        inputs.forEach((id, input) -> groups.computeIfAbsent(input, ignored -> new ArrayList<>()).add(id));
        var assigned = new HashMap<String, WorkerCandidate>();
        groups.forEach((input, ids) -> {
            var candidates = poll.apply(input, ids.size());
            for (int i = 0; i < candidates.size(); i++) assigned.put(ids.get(i), candidates.get(i));
        });
        var result = new LinkedHashMap<String, WorkerCandidate>();
        inputs.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
        return Collections.unmodifiableMap(result);
    }
}
