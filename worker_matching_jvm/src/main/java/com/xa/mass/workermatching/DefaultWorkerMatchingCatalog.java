package com.xa.mass.workermatching;

import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.*;
import java.util.function.LongSupplier;

/** Pure query admission/correlation with caller-driven Pool supply coordination. */
public final class DefaultWorkerMatchingCatalog implements WorkerMatchingCatalog {
    private static final int MAX_TAKE_REQUESTS = 100;
    private final Map<String, QueryFunction> queryFunctions;
    private final Map<String, MatchingGroup> groups;
    private final Set<String> globalFunctions;
    private final PoolRefillCoordinator refillCoordinator;

    public DefaultWorkerMatchingCatalog(CandidateBudget budget, Map<String, WorkerCandidatePool> pools,
            LongSupplier clock, Map<String, PoolRefillPolicy> poolPolicies,
            Map<String, QueryFunction> queryFunctions, Map<String, MatchingGroup> groups,
            List<String> poolOrder, Set<String> globalFunctions) {
        this.queryFunctions = Map.copyOf(queryFunctions);
        this.groups = Map.copyOf(groups);
        this.globalFunctions = Set.copyOf(globalFunctions);
        if (!queryFunctions.keySet().containsAll(this.globalFunctions))
            throw new IllegalArgumentException("unknown global function");
        queryFunctions.keySet().forEach(id -> requireNonBlank(id, "executorName"));
        this.refillCoordinator = new PoolRefillCoordinator(budget, pools, clock, poolPolicies, groups, poolOrder);
        groups.forEach((group, configuration) -> {
            for (String name : configuration.functions())
                if (!queryFunctions.containsKey(name)) throw new IllegalArgumentException("Unknown function: " + name);
        });
    }

    private QueryFunction requireFunction(String group, String name) {
        requireNonBlank(group, "workerGroupId"); requireNonBlank(name, "executorName");
        var function = queryFunctions.get(name);
        if (function == null || !globalFunctions.contains(name)
                && !groups.getOrDefault(group, new MatchingGroup(Set.of(),Set.of(), null)).functions().contains(name))
            throw new IllegalArgumentException("unavailable Matching function");
        return function;
    }

    @Override public WorkerQuery normalizeQuery(String group, WorkerQuery query) {
        Objects.requireNonNull(query, "query");
        var function = requireFunction(group, query.executorName());
        return new WorkerQuery(query.executorName(), function.normalizeInput(group, query.input()));
    }

    @Override public Map<String,WorkerCandidate> take(String group, Map<String,WorkerQuery> queriesByMessageId) {
        requireNonBlank(group, "workerGroupId");
        Objects.requireNonNull(queriesByMessageId, "queriesByMessageId");
        if (queriesByMessageId.size() > MAX_TAKE_REQUESTS) throw new IllegalArgumentException("at most " + MAX_TAKE_REQUESTS + " Item queries");
        var captured = new LinkedHashMap<String,WorkerQuery>();
        queriesByMessageId.forEach((id, query) -> {
            requireNonBlank(id, "messageId"); captured.put(id, Objects.requireNonNull(query, "query"));
        });
        // Complete every function's pure admission before any executor can consume inventory.
        var grouped = new LinkedHashMap<String,Map<String,Object>>();
        captured.forEach((id, query) -> {
            var normalized = normalizeQuery(group, query);
            grouped.computeIfAbsent(normalized.executorName(), ignored -> new LinkedHashMap<>()).put(id, normalized.input());
        });
        var assigned = new HashMap<String,WorkerCandidate>();
        var workers = new HashSet<String>();
        grouped.forEach((name, inputs) -> {
            var result = Objects.requireNonNull(queryFunctions.get(name).apply(group, Collections.unmodifiableMap(inputs)), "executor result");
            for (var row : result.entrySet()) {
                if (!inputs.containsKey(row.getKey()) || row.getValue() == null)
                    throw new IllegalStateException("executor returned an unrequested or null candidate");
            }
            inputs.keySet().forEach(id -> {
                var candidate = result.get(id);
                if (candidate != null && workers.add(candidate.workerId())) assigned.put(id, candidate);
            });
        });
        var result = new LinkedHashMap<String,WorkerCandidate>();
        captured.keySet().forEach(id -> { if (assigned.containsKey(id)) result.put(id, assigned.get(id)); });
        return Collections.unmodifiableMap(result);
    }

    @Override public Map<String, Integer> observeRefillDeficits(Map<String, List<RefillTarget>> supplied) {
        return refillCoordinator.observeRefillDeficits(supplied);
    }

    @Override public int refill(String group, List<RefillTarget> declarations, Map<String, Long> offered) {
        return refillCoordinator.refill(group, declarations, offered);
    }

    @Override public List<RefillTarget> normalizeRefill(String group, List<RefillTarget> declarations) {
        return refillCoordinator.normalizeRefill(group, declarations);
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

}
