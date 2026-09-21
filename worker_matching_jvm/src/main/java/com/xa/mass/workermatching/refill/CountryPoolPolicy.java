package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.workermatching.RuleInputs;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;
import java.util.function.BiFunction;
import org.jspecify.annotations.Nullable;

/** Country supply over bounded Facts reads and a single bucket-count snapshot. */
public final class CountryPoolPolicy extends PoolMaintenance<Map<String, Object>> {
    private final BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts;
    public CountryPoolPolicy(WorkerCandidatePool pool,
            BiFunction<String, List<String>, Map<String, Map<String, Object>>> readFacts) {
        super(pool); this.readFacts = Objects.requireNonNull(readFacts);
    }
    @Override public TargetBatching targetBatching() { return TargetBatching.ALL; }
    @Override protected EligibilityQuery normalize(String group, EligibilityQuery query) {
        if (query.query().isEmpty()) return query;
        if (!query.query().keySet().equals(Set.of("worker.country")))
            throw new IllegalArgumentException("country Pool only accepts worker.country");
        return new EligibilityQuery(Map.of("worker.country", RuleInputs.countries(query.query().get("worker.country"))));
    }
    @Override protected Map<String, Map<String, Object>> readQualifications(String group, List<String> ids) {
        return readFacts.apply(group, ids);
    }
    @Override protected @Nullable String bucketKey(String group, String id, @Nullable Map<String, Object> facts) {
        return facts != null && facts.get("country") instanceof String country && RuleInputs.validCountry(country)
                ? country : null;
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
