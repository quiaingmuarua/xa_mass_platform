package com.xa.mass.workermatching.refill;

import com.xa.mass.workermatching.pool.CandidatePool;
import com.xa.mass.workermatching.index.PartitionedZsetIndex;
import com.xa.mass.workermatching.index.CountryIndex;
import java.util.function.LongSupplier;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;
import com.xa.mass.workermatching.pool.CandidatePool.Selection;
import static com.xa.mass.workermatching.pool.CandidatePool.*;

/** Supply interpretation over the existing partitioned source indexes. */
abstract class PartitionedPoolPolicy extends PoolMaintenance<PartitionedZsetIndex.Projection> {
    protected record Criteria(String partition, String kind, List<String> values) {}
    private final PartitionedZsetIndex index;
    PartitionedPoolPolicy(LongSupplier clock, CandidatePool pool, PartitionedZsetIndex index) {
        super(clock, pool); this.index = Objects.requireNonNull(index);
    }
    abstract Criteria criteria(Map<String, List<String>> query);

    @Override protected EligibilityQuery normalize(String group, EligibilityQuery input) {
        var expression = input.query();
        if (expression.containsKey("workerId")) throw new IllegalArgumentException("identity targeting is not a Pool target");
        var query = RuleQueries.normalize(input); criteria(query.query()); return query;
    }
    @Override protected Selection target(String group, EligibilityQuery query) {
        var criteria = criteria(query.query());
        return selection(criteria);
    }
    protected static Selection selection(Criteria criteria) {
        if (criteria.partition().isEmpty())
            return criteria.kind().equals("any") ? all() : range("country", criteria.values());
        return criteria.kind().equals("any") ? range("partition:" + criteria.partition(), List.of("1"))
                : range("country-partition:" + criteria.partition(), criteria.values());
    }
    @Override protected Map<String, String> memberships(String group, String id, PartitionedZsetIndex.Projection projection) {
        if (projection == null) return null;
        var views = new LinkedHashMap<String, String>();
        views.put("country", projection.prefix());
        for (String partition : projection.partitions()) {
            views.put("partition:" + partition, "1");
            views.put("country-partition:" + partition, projection.prefix());
        }
        return views;
    }
    @Override protected Map<String, PartitionedZsetIndex.Projection> readQualifications(String group, List<String> ids) {
        return index.snapshot(group, ids);
    }
    static Criteria countries(Map<String,List<String>> query,Set<String> supported,String partition) {
        if(!supported.containsAll(query.keySet()))throw new IllegalArgumentException("unsupported Pool target condition");
        if(!query.containsKey("worker.country"))return new Criteria(partition,"any",List.of());
        var codes=query.get("worker.country").stream().map(v->Integer.toString(CountryIndex.code(v))).distinct().toList();
        return new Criteria(partition,"countries",codes);
    }
}
