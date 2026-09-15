package com.xa.mass.workermatching.rules;

import com.xa.mass.workermatching.EligibilityQuery;
import java.util.*;
import java.util.function.BiPredicate;

/** Identity Eligibility without facts, with country queries only where that index is enabled. */
public final class DefaultRuleHandler extends LocalCandidateRule<PartitionedZsetIndex.Projection> {
    private final Set<String> countryGroups;
    public DefaultRuleHandler(RedisRuleStorage storage, Map<String, Set<String>> groupRules) {
        super(storage);
        var groups = new HashSet<String>();
        groupRules.forEach((group, rules) -> { if (rules.contains("worker.country")) groups.add(group); });
        countryGroups = Set.copyOf(groups);
    }
    @Override protected EligibilityQuery normalize(String group, Map<String, ?> expression, int count, boolean selector) {
        if (expression.containsKey("workerId") && expression.size() != 1)
            throw new IllegalArgumentException("workerId cannot be combined with property conditions");
        if (selector && !expression.isEmpty() && !expression.containsKey("workerId")) RuleQueries.requireConditions(expression);
        var query = RuleQueries.normalize(expression, count);
        if (!query.query().isEmpty() && !query.query().containsKey("workerId")) {
            if (!countryGroups.contains(group)) throw new IllegalArgumentException("country index unavailable");
            PartitionedRuleHandler.countries(query.query(), Set.of("worker.country"), "");
        }
        return query;
    }
    @Override protected int targetCount(EligibilityQuery target) {
        return target.query().containsKey("workerId")
                ? Math.min(target.count(), new HashSet<>(target.query().get("workerId")).size()) : target.count();
    }
    @Override protected BiPredicate<String, PartitionedZsetIndex.Projection> predicate(String group, EligibilityQuery query) {
        if (query.query().isEmpty()) return (id, value) -> true;
        if (query.query().containsKey("workerId")) {
            var ids = Set.copyOf(query.query().get("workerId"));
            return (id, value) -> ids.contains(id);
        }
        var criteria = PartitionedRuleHandler.countries(query.query(), Set.of("worker.country"), "");
        return (id, value) -> value != null && value.matches(id, criteria);
    }
    @Override protected Map<String, PartitionedZsetIndex.Projection> readQualifications(String group, List<String> ids) {
        return countryGroups.contains(group)
                ? new PartitionedZsetIndex(storage::commands, storage.indexKey(group, "country")).snapshot(ids) : Map.of();
    }
}
