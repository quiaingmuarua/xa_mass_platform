package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.pool.CandidatePool.Selection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Call-local grouping and association of already interpreted Pool selections. */
final class PoolCandidates {
    private PoolCandidates() { }

    static Map<String, WorkerCandidate> take(CandidatePool pool, String group, Map<String, Selection> selections) {
        var groups = new LinkedHashMap<Selection, List<String>>();
        selections.forEach((id, selection) -> groups.computeIfAbsent(selection, ignored -> new ArrayList<>()).add(id));
        var limits = new LinkedHashMap<Selection, Integer>();
        groups.forEach((selection, ids) -> limits.put(selection, ids.size()));
        var taken = pool.take(group, limits);
        var assigned = new HashMap<String, WorkerCandidate>();
        groups.forEach((selection, ids) -> {
            var candidates = taken.get(selection);
            for (int i = 0; i < candidates.size(); i++) assigned.put(ids.get(i), candidates.get(i));
        });
        var result = new LinkedHashMap<String, WorkerCandidate>();
        selections.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
        return Collections.unmodifiableMap(result);
    }
}
