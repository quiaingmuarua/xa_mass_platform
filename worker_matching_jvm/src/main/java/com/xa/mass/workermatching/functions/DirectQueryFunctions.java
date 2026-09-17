package com.xa.mass.workermatching.functions;

import com.xa.mass.workermatching.index.PhoneIndex;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.QueryFunctions;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Fixed identity and property lookup strategies. Neither acquires leases nor uses Pool stock. */
public final class DirectQueryFunctions {
    private final PhoneIndex phones;

    public DirectQueryFunctions(PhoneIndex phones) {
        this.phones = Objects.requireNonNull(phones);
    }

    public static QueryFunctions identity() {
        return new QueryFunctions(DirectQueryFunctions::identityInput, (group, inputs) -> {
            bounded(inputs);
            var result = new LinkedHashMap<String, WorkerCandidate>();
            inputs.forEach((id, input) -> result.put(id, new WorkerCandidate(identityInput(group, input), 0)));
            return Collections.unmodifiableMap(result);
        });
    }

    public QueryFunctions phone() {
        return new QueryFunctions(DirectQueryFunctions::phoneInput, this::lookupPhones);
    }

    private Map<String, WorkerCandidate> lookupPhones(String group, Map<String, Object> inputs) {
        bounded(inputs);
        var requests = new LinkedHashMap<String, List<String>>();
        inputs.forEach((id, input) -> requests.computeIfAbsent(phoneInput(group, input), ignored -> new ArrayList<>()).add(id));
        var counts = new LinkedHashMap<String, Integer>();
        requests.forEach((phone, ids) -> counts.put(phone, ids.size()));
        var found = phones.lookup(group, counts);
        var assigned = new LinkedHashMap<String, WorkerCandidate>();
        requests.forEach((phone, ids) -> {
            var workers = found.get(phone);
            for (int i = 0; i < workers.size(); i++) assigned.put(ids.get(i), new WorkerCandidate(workers.get(i), 0));
        });
        var result = new LinkedHashMap<String, WorkerCandidate>();
        inputs.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
        return Collections.unmodifiableMap(result);
    }

    private static String identityInput(String group, Object input) {
        group(group);
        if (!(input instanceof String id) || id.isBlank()) throw new IllegalArgumentException("workerId requires a nonblank string");
        return id;
    }

    private static String phoneInput(String group, Object input) {
        group(group);
        if (!(input instanceof String phone) || phone.isEmpty()) throw new IllegalArgumentException("worker.phone requires a nonempty string");
        return phone;
    }

    private static void group(String group) {
        if (group == null || group.isBlank()) throw new IllegalArgumentException("workerGroupId must be nonblank");
    }

    private static void bounded(Map<String, Object> inputs) {
        Objects.requireNonNull(inputs, "inputs");
        if (inputs.size() > 100) throw new IllegalArgumentException("at most 100 requests");
        inputs.keySet().forEach(id -> {
            if (id == null || id.isBlank()) throw new IllegalArgumentException("messageId must be nonblank");
        });
    }
}
