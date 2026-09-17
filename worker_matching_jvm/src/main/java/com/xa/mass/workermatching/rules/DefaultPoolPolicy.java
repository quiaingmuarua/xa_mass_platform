package com.xa.mass.workermatching.rules;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;
import com.xa.mass.workermatching.rules.CandidatePool.Selection;
import static com.xa.mass.workermatching.rules.CandidatePool.*;

/** Identity Eligibility without facts, with country queries only where that index is enabled. */
public final class DefaultPoolPolicy extends PoolMaintenance<PartitionedZsetIndex.Projection> {
    private final Set<String> countryGroups;
    public DefaultPoolPolicy(MatchingStorage storage, CandidatePool pool, Set<String> countryGroups) {
        super(storage, pool);
        this.countryGroups = Set.copyOf(countryGroups);
    }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery input) {
        var expression = input.query();
        if (expression.containsKey("workerId") && expression.size() != 1)
            throw new IllegalArgumentException("workerId cannot be combined with property conditions");
        var query = RuleQueries.normalize(input);
        if (!query.query().isEmpty() && !query.query().containsKey("workerId")) {
            if (!countryGroups.contains(group)) throw new IllegalArgumentException("country index unavailable");
            PartitionedPoolPolicy.countries(query.query(), Set.of("worker.country"), "");
        }
        return query;
    }
    @Override protected Selection target(String group, EligibilityQuery query) {
        if (query.query().isEmpty()) return all();
        if (query.query().containsKey("workerId")) return identities(query.query().get("workerId"));
        return range("country", RuleInputs.codes(query.query().get("worker.country")));
    }
    @Override protected Map<String, String> memberships(String group, String id, PartitionedZsetIndex.Projection projection) {
        return projection == null ? Map.of() : Map.of("country", projection.prefix());
    }
    @Override protected Map<String, PartitionedZsetIndex.Projection> readQualifications(String group, List<String> ids) {
        return countryGroups.contains(group)
                ? new PartitionedZsetIndex(storage::commands, storage.indexKey(group, "country")).snapshot(ids) : Map.of();
    }
}
