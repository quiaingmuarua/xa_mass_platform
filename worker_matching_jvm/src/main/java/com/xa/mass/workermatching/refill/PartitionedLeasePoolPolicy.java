package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.*;
import com.xa.mass.workermatching.PlatformLeaseState.Coordinate;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.BiFunction;

/** Caller-bounded qualification; missing/full partitions generate no lease reads. */
public final class PartitionedLeasePoolPolicy implements PoolRefillPolicy {
    static final int PAIR_BUDGET = 1000;
    private final WorkerCandidatePool pool;
    private final BiFunction<String, List<String>, Map<String, Map<String, Object>>> facts;
    private final PlatformLeaseState leases;
    private final PartitionedLeasePoolDefinition definition;
    private final Map<String, Integer> cursors = new ConcurrentHashMap<>();
    private final LongAdder checked = new LongAdder(), references = new LongAdder();
    public PartitionedLeasePoolPolicy(WorkerCandidatePool pool,
            BiFunction<String, List<String>, Map<String, Map<String, Object>>> facts,
            PlatformLeaseState leases, PartitionedLeasePoolDefinition definition) {
        this.pool = pool; this.facts = facts; this.leases = leases; this.definition = definition;
    }
    @Override public TargetBatching targetBatching() { return TargetBatching.PAGED; }
    @Override public EligibilityQuery normalizeQuery(String group, EligibilityQuery query) {
        if (!query.query().keySet().equals(Set.of("partition", "worker.country")))
            throw new IllegalArgumentException("lease target requires partition and worker.country");
        var partitions = RuleInputs.strings(query.query().get("partition"));
        var countries = RuleInputs.countries(query.query().get("worker.country"));
        if (partitions.size() != 1 || countries.size() != 1)
            throw new IllegalArgumentException("lease target requires one partition and one country");
        definition.bucket(partitions.getFirst(), countries.getFirst());
        return new EligibilityQuery(Map.of("partition", partitions, "worker.country", countries));
    }
    private String bucket(EligibilityQuery query) {
        return definition.bucket(query.query().get("partition").getFirst(), query.query().get("worker.country").getFirst());
    }
    private Map<EligibilityQuery, Integer> admitted(String group, Map<EligibilityQuery, Integer> targets) {
        if (targets.size() > 100) throw new IllegalArgumentException("at most 100 lease targets");
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        targets.forEach((query, count) -> {
            if (count == null || count < 1 || count > 1000) throw new IllegalArgumentException("target requires 1..1000");
            result.merge(normalizeQuery(group, query), count, Math::max);
        });
        return result;
    }
    @Override public Map<EligibilityQuery, Integer> deficits(String group, Map<EligibilityQuery, Integer> targets) {
        var normalized = admitted(group, targets);
        var counts = pool.liveCountByKey(group);
        int room = pool.leaseSupplyCapacity(group);
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        targets.forEach((query, count) -> result.put(query, Math.min(room,
                Math.max(0, normalized.get(normalizeQuery(group, query)) - counts.getOrDefault(bucket(query), 0)))));
        return Collections.unmodifiableMap(result);
    }
    @Override public List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
            Map<String, Long> offered, int maxAccepted) {
        var normalized = admitted(group, targets);
        if (maxAccepted < 0) throw new IllegalArgumentException("negative admission budget");
        offered.forEach((id, score) -> { RuleInputs.text(id); if (score == null || score == 0) throw new IllegalArgumentException("strict candidate required"); });
        if (offered.isEmpty() || maxAccepted == 0) return List.of();
        var counts = pool.liveCountByKey(group);
        var missing = normalized.entrySet().stream().filter(e -> counts.getOrDefault(bucket(e.getKey()), 0) < e.getValue()).toList();
        if (missing.isEmpty() || pool.leaseSupplyCapacity(group) == 0) return List.of();
        List<String> ids = offered.keySet().stream().limit(maxAccepted).toList();
        var values = facts.apply(group, ids);
        var byCountry = new LinkedHashMap<String, List<String>>();
        for (String id : ids) {
            var row = values.get(id);
            if (row == null || !(row.get(definition.subjectProperty()) instanceof String subject) || subject.isBlank()
                    || !(row.get(definition.countryProperty()) instanceof String country) || !RuleInputs.validCountry(country)) continue;
            byCountry.computeIfAbsent(country, ignored -> new ArrayList<>()).add(id);
        }
        int start = Math.floorMod(cursors.getOrDefault(group, 0), missing.size());
        var pairs = new LinkedHashMap<Coordinate, String>();
        var targetCounts = new LinkedHashMap<String, Integer>();
        int visited = 0;
        for (; visited < missing.size() && pairs.size() < PAIR_BUDGET; visited++) {
            var target = missing.get((start + visited) % missing.size());
            var query = target.getKey();
            String partition = query.query().get("partition").getFirst(), country = query.query().get("worker.country").getFirst();
            String key = bucket(query);
            targetCounts.put(key, target.getValue());
            for (String id : byCountry.getOrDefault(country, List.of())) {
                pairs.put(new Coordinate((String) values.get(id).get(definition.subjectProperty()), partition, id), key);
                if (pairs.size() == PAIR_BUDGET) break;
            }
        }
        cursors.put(group, (start + Math.max(1, visited)) % missing.size());
        if (pairs.isEmpty()) return List.of();
        checked.add(pairs.size());
        var deadlines = leases.read(group, definition.poolName(), List.copyOf(pairs.keySet()));
        var candidates = new LinkedHashMap<WorkerCandidate, Map<String, Long>>();
        pairs.forEach((coordinate, key) -> {
            candidates.computeIfAbsent(new WorkerCandidate(coordinate.workerId(), offered.get(coordinate.workerId())),
                    ignored -> new LinkedHashMap<>()).put(key, deadlines.getOrDefault(coordinate, 0L));
        });
        var accepted = pool.offerSharedBatch(group, candidates, targetCounts);
        references.add(candidates.values().stream().mapToInt(Map::size).sum());
        return accepted.stream().map(WorkerCandidate::workerId).distinct().toList();
    }
    public Map<String, Long> metrics() { return Map.of("checkedCoordinates", checked.sum(), "capturedReferences", references.sum()); }
}
