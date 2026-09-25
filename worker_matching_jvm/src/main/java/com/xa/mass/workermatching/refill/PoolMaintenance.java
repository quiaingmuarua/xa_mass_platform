package com.xa.mass.workermatching.refill;

import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerMatching.WorkerCandidate;
import com.xa.mass.workermatching.PoolRefillPolicy;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import java.util.*;
import org.jspecify.annotations.Nullable;

/** Bounded qualification and batch admission, independent of query consumption. */
public abstract class PoolMaintenance<P> implements PoolRefillPolicy {
    private final WorkerCandidatePool pool;
    protected PoolMaintenance(WorkerCandidatePool pool) { this.pool = Objects.requireNonNull(pool); }
    @Override public TargetBatching targetBatching() { return TargetBatching.PAGED; }
    protected abstract EligibilityQuery normalize(String group, EligibilityQuery query);
    protected abstract Map<String, P> readQualifications(String group, List<String> offered);
    /** Null means ineligible; every qualified offer belongs to exactly one bucket. */
    protected abstract @Nullable String bucketKey(String group, String workerId, @Nullable P qualification);
    /** Interprets only the supplied bucket directory, never candidate entries. */
    protected abstract Map<EligibilityQuery, Set<String>> matchingKeys(String group,
            Collection<EligibilityQuery> queries, Set<String> bucketKeys);

    @Override public final EligibilityQuery normalizeQuery(String group, EligibilityQuery query) {
        identity(group); return normalize(group, Objects.requireNonNull(query, "query"));
    }

    private Map<EligibilityQuery, EligibilityQuery> targets(String group, Map<EligibilityQuery, Integer> targets) {
        identity(group); Objects.requireNonNull(targets);
        int limit = targetBatching() == TargetBatching.ALL ? 10_000 : 100;
        if (targets.size() > limit) throw new IllegalArgumentException("at most " + limit + " targets");
        var normalized = new LinkedHashMap<EligibilityQuery, EligibilityQuery>();
        targets.forEach((query, count) -> {
            if (count == null || count < 1 || count > 1000) throw new IllegalArgumentException("target count requires 1..1000");
            normalized.put(query, normalizeQuery(group, query));
        });
        return normalized;
    }

    @Override public final Map<EligibilityQuery, Integer> deficits(String group, Map<EligibilityQuery, Integer> targets) {
        var normalized = targets(group, targets);
        var counts = pool.countByKey(group);
        var matching = matchingKeys(group, normalized.values(), counts.keySet());
        int room = pool.remainingCapacity(group);
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        normalized.forEach((query, value) -> {
            int resident = matching.get(value).stream().mapToInt(key -> counts.getOrDefault(key, 0)).sum();
            result.put(query, room == 0 ? 0 : Math.max(0, targets.get(query) - resident));
        });
        return Collections.unmodifiableMap(result);
    }

    @Override public final List<String> refill(String group, Map<EligibilityQuery, Integer> targets,
            Map<String, Long> offered, int maxAccepted) {
        var normalized = targets(group, targets);
        Objects.requireNonNull(offered);
        if (offered.size() > 100 || maxAccepted < 0 || maxAccepted > 100)
            throw new IllegalArgumentException("at most 100 offers and maxAccepted in 0..100");
        offered.forEach((id, score) -> {
            identity(id);
            if (score == null || score == 0) throw new IllegalArgumentException("strict candidate required");
        });
        if (maxAccepted == 0 || offered.isEmpty() || normalized.isEmpty()) return List.of();
        var values = readQualifications(group, List.copyOf(offered.keySet()));
        if (!offered.keySet().containsAll(values.keySet()))
            throw new IllegalStateException("Pool policy read an unoffered identity");
        // Finish all fallible qualification and key interpretation before any admission.
        var keysByWorker = new LinkedHashMap<String, String>();
        offered.forEach((id, score) -> {
            String key = bucketKey(group, id, values.get(id));
            if (key != null) { identity(key); keysByWorker.put(id, key); }
        });
        var matching = matchingKeys(group, normalized.values(), Set.copyOf(keysByWorker.values()));
        var allowed = new HashSet<String>();
        matching.values().forEach(allowed::addAll);
        var batches = new LinkedHashMap<String, List<WorkerCandidate>>();
        int selected = 0;
        for (var entry : keysByWorker.entrySet()) {
            if (selected == maxAccepted) break;
            if (!allowed.contains(entry.getValue())) continue;
            batches.computeIfAbsent(entry.getValue(), ignored -> new ArrayList<>())
                    .add(new WorkerCandidate(entry.getKey(), offered.get(entry.getKey())));
            selected++;
        }
        var accepted = new ArrayList<String>();
        batches.forEach((key, candidates) -> pool.offerBatch(group, key, candidates)
                .forEach(candidate -> accepted.add(candidate.workerId())));
        return List.copyOf(accepted);
    }

    private static void identity(String id) {
        if (id == null || id.isBlank()) throw new IllegalArgumentException("non-blank identity required");
    }
}
