package com.xa.mass.workermatching.functions;

import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.MessagingEligibility;
import com.xa.mass.workermatching.QueryFunction;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.index.PropertyIndex;
import java.util.*;
import java.util.function.BiFunction;

/** Bounded Phone lookup followed by Messaging qualification; never consumes Pool stock. */
public final class MessagingPhoneQueryFunction implements QueryFunction {
    private final PropertyIndex phones;
    private final BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts;

    public MessagingPhoneQueryFunction(PropertyIndex phones,
            BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts) {
        this.phones = Objects.requireNonNull(phones);
        this.readFacts = Objects.requireNonNull(readFacts);
    }

    @Override public Object normalizeInput(String workerGroupId, Object input) {
        var values = RuleInputs.object(input, Set.of("phone", "country"));
        values.put("phone", RuleInputs.text(values.get("phone")));
        if (values.containsKey("country")) values.put("country", RuleInputs.countries(values.get("country")));
        return Collections.unmodifiableMap(values);
    }

    @Override public Map<String, WorkerCandidate> apply(String workerGroupId, Map<String, Object> inputsByMessageId) {
        if (inputsByMessageId.isEmpty()) return Map.of();
        var valuesToFind = new LinkedHashSet<String>();
        inputsByMessageId.values().forEach(input -> valuesToFind.add((String) ((Map<?, ?>) input).get("phone")));
        var found = phones.lookup(workerGroupId, List.copyOf(valuesToFind));
        var ids = new LinkedHashSet<>(found.values());
        if (ids.isEmpty()) return Map.of();
        var facts = readFacts.apply(workerGroupId, List.copyOf(ids));
        var used = new HashSet<String>();
        var result = new LinkedHashMap<String, WorkerCandidate>();
        inputsByMessageId.forEach((messageId, input) -> {
            var values = (Map<?, ?>) input;
            String phone = (String) values.get("phone");
            @SuppressWarnings("unchecked") var countries = (List<String>) values.get("country");
            String workerId = found.get(phone);
            if (workerId == null) return;
            var workerFacts = facts.get(workerId);
            String country = MessagingEligibility.country(workerFacts);
            if (country == null || !phone.equals(workerFacts.get("phone"))
                    || (countries != null && !countries.contains(country)) || !used.add(workerId)) return;
            result.put(messageId, new WorkerCandidate(workerId, 0));
        });
        return Collections.unmodifiableMap(result);
    }
}
