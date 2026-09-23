package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.MessagingEligibility;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

public final class MessagingPoolPolicy extends PoolMaintenance<Map<String, Object>> {
    private final BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts;
    public MessagingPoolPolicy(WorkerCandidatePool pool,
            BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts) {
        super(pool); this.readFacts = Objects.requireNonNull(readFacts);
    }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery input) {
        if (!Set.of("worker.country").containsAll(input.query().keySet()))
            throw new IllegalArgumentException("unsupported Messaging target condition");
        var query = RuleQueries.normalize(input);
        if (query.query().containsKey("worker.country")) RuleInputs.countries(query.query().get("worker.country"));
        return query;
    }
    @Override protected Map<String, Map<String, Object>> readQualifications(String group, List<String> ids) {
        return readFacts.apply(group, ids);
    }
    @Override protected @Nullable String bucketKey(String group, String id, @Nullable Map<String, Object> facts) {
        return MessagingEligibility.country(facts);
    }
    @Override protected Map<EligibilityQuery, Set<String>> matchingKeys(String group,
            Collection<EligibilityQuery> queries, Set<String> keys) {
        var result = new LinkedHashMap<EligibilityQuery, Set<String>>();
        for (var query : queries) {
            if (query.query().isEmpty()) result.put(query, keys);
            else {
                var countries = new HashSet<>(query.query().get("worker.country"));
                countries.retainAll(keys);
                result.put(query, countries);
            }
        }
        return result;
    }
}
