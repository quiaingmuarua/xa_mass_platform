package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.index.PhoneIndex;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact property lookup over an existing index; neither consumes stock nor acquires leases. */
public final class PhoneQueryFunction implements QueryFunction {
    private final PhoneIndex phones;

    public PhoneQueryFunction(PhoneIndex phones) {
        this.phones = Objects.requireNonNull(phones);
    }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        if (!(input instanceof String phone) || phone.isEmpty())
            throw new IllegalArgumentException("worker.phone requires a nonempty string");
        return phone;
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        var requests = new LinkedHashMap<String, List<String>>();
        inputsByMessageId.forEach((id, input) -> requests.computeIfAbsent((String) input, ignored -> new ArrayList<>()).add(id));
        var counts = new LinkedHashMap<String, Integer>();
        requests.forEach((phone, ids) -> counts.put(phone, ids.size()));
        var found = phones.lookup(workerGroupId, counts);
        var assigned = new LinkedHashMap<String, WorkerCandidate>();
        requests.forEach((phone, ids) -> {
            var workers = found.get(phone);
            for (int i = 0; i < workers.size(); i++) assigned.put(ids.get(i), new WorkerCandidate(workers.get(i), 0));
        });
        var result = new LinkedHashMap<String, WorkerCandidate>();
        inputsByMessageId.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
        return Collections.unmodifiableMap(result);
    }
}
