package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;

/** Identity Eligibility without facts, with country queries only where that index is enabled. */
public final class DefaultRuleHandler extends PoolRule<PartitionedZsetIndex.Projection> {
    private final Set<String> countryGroups;
    public DefaultRuleHandler(RedisRuleStorage storage, Map<String, Set<String>> groupRules) {
        super(storage);
        var groups = new HashSet<String>();
        groupRules.forEach((group, rules) -> { if (rules.contains("worker.country")) groups.add(group); });
        countryGroups = Set.copyOf(groups);
    }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery input) {
        var expression = input.query();
        if (expression.containsKey("workerId") && expression.size() != 1)
            throw new IllegalArgumentException("workerId cannot be combined with property conditions");
        var query = RuleQueries.normalize(input);
        if (!query.query().isEmpty() && !query.query().containsKey("workerId")) {
            if (!countryGroups.contains(group)) throw new IllegalArgumentException("country index unavailable");
            PartitionedRuleHandler.countries(query.query(), Set.of("worker.country"), "");
        }
        return query;
    }
    @Override protected Selection target(String group, EligibilityQuery query) {
        if (query.query().isEmpty()) return all();
        if (query.query().containsKey("workerId")) return identities(query.query().get("workerId"));
        return range("country", RuleInputs.codes(query.query().get("worker.country")));
    }
    @Override protected Object normalizeLocalInput(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("workerId", "country"));
        if (values.containsKey("workerId")) {
            if (values.size() != 1) throw new IllegalArgumentException("workerId cannot combine with properties");
            values.put("workerId", RuleInputs.strings(values.get("workerId")));
        }
        if (values.containsKey("country")) {
            if (!countryGroups.contains(group)) throw new IllegalArgumentException("country index unavailable");
            values.put("country", RuleInputs.countries(values.get("country")));
        }
        return Collections.unmodifiableMap(values);
    }
    @Override protected Selection select(String group, Object input) {
        var values = RuleInputs.object(input, Set.of("workerId", "country"));
        if (values.isEmpty()) return all();
        if (values.containsKey("workerId")) return identities(RuleInputs.strings(values.get("workerId")));
        return range("country", RuleInputs.codes(values.get("country")));
    }
    @Override protected Map<String, String> memberships(String group, String id, PartitionedZsetIndex.Projection projection) {
        return projection == null ? Map.of() : Map.of("country", projection.prefix());
    }
    @Override protected Map<String, PartitionedZsetIndex.Projection> readQualifications(String group, List<String> ids) {
        return countryGroups.contains(group)
                ? new PartitionedZsetIndex(storage::commands, storage.indexKey(group, "country")).snapshot(ids) : Map.of();
    }
}
