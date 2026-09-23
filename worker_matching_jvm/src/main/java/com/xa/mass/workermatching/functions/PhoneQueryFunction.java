package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.index.PropertyIndex;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Exact property lookup over an existing index; neither consumes stock nor acquires leases. */
public final class PhoneQueryFunction implements QueryFunction {
    private final PropertyIndex phones;

    public PhoneQueryFunction(PropertyIndex phones) {
        this.phones = Objects.requireNonNull(phones);
    }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        if (!(input instanceof String phone) || phone.isEmpty())
            throw new IllegalArgumentException("worker.phone requires a nonempty string");
        return phone;
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        var values = new LinkedHashSet<String>();
        inputsByMessageId.values().forEach(input -> values.add((String) input));
        if (values.isEmpty()) return Map.of();
        var found = phones.lookup(workerGroupId, List.copyOf(values));
        var used = new HashSet<String>();
        var result = new LinkedHashMap<String, WorkerCandidate>();
        inputsByMessageId.forEach((id, input) -> {
            String worker = found.get(input);
            if (worker != null && used.add(worker)) result.put(id, new WorkerCandidate(worker, 0));
        });
        return Collections.unmodifiableMap(result);
    }
}
