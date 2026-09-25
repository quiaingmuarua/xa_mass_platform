package com.xa.mass.workermatching;

import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.WorkerCandidatePool;
import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.*;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** Caller-driven Pool supply coordination; the single-flight Refill Producer owns scheduling. */
final class PoolRefillCoordinator {
    private final CandidateBudget budget;
    private final Map<String, WorkerCandidatePool> pools;
    private final Map<String, PoolRefillPolicy> poolPolicies;
    private final Map<String, MatchingGroup> groups;
    private final Map<String, Integer> poolPositions;
    private record Scope(String workerGroupId, String poolName) { }
    private final LongSupplier clock;
    private final Map<String, Integer> poolRotationByGroup = new LinkedHashMap<>();
    private final Map<Scope, Integer> targetPagesByScope = new LinkedHashMap<>();
    private long lastDiagnosticMillis;
    private long requestedDeficit;

    PoolRefillCoordinator(CandidateBudget budget, Map<String, WorkerCandidatePool> pools,
            LongSupplier clock, Map<String, PoolRefillPolicy> poolPolicies,
            Map<String, MatchingGroup> groups, List<String> poolOrder) {
        this.budget = Objects.requireNonNull(budget, "budget");
        this.pools = Map.copyOf(pools);
        this.clock = Objects.requireNonNull(clock, "clock");
        this.poolPolicies = Map.copyOf(poolPolicies);
        this.groups = Map.copyOf(groups);
        var positions=new LinkedHashMap<String,Integer>();
        for (String name : poolOrder) {
            if (positions.putIfAbsent(name, positions.size()) != null)
                throw new IllegalArgumentException("duplicate Pool in rotation order");
        }
        if (!positions.keySet().equals(poolPolicies.keySet()))
            throw new IllegalArgumentException("rotation order must name every Pool policy exactly once");
        this.poolPositions=Map.copyOf(positions);
        poolPolicies.keySet().forEach(id->requireNonBlank(id,"Pool name"));
        var instances=Collections.newSetFromMap(new java.util.IdentityHashMap<PoolRefillPolicy,Boolean>());
        poolPolicies.values().forEach(handler->{
            if(!instances.add(handler))throw new IllegalArgumentException("one Pool name per maintenance instance");
        });
        groups.forEach((group, configuration) -> {
            requireNonBlank(group, "WorkerGroup");
            for (String name : configuration.pools())
                if (!poolPolicies.containsKey(name)) throw new IllegalArgumentException("Unknown Pool: " + name);
        });
    }

    private @Nullable PoolRefillPolicy poolPolicy(String group,String id) {
        var handler=poolPolicies.get(id);
        var enabled=groups.getOrDefault(group,new MatchingGroup(Set.of(),Set.of(), null)).pools();
        return handler==null || !enabled.contains(id) ? null : handler;
    }

    private PoolRefillPolicy requirePoolPolicy(String group,String poolName) {
        requireNonBlank(group,"workerGroupId"); requireNonBlank(poolName,"poolName");
        var handler=poolPolicy(group,poolName);
        if(handler==null)throw new IllegalArgumentException("unavailable Pool");
        return handler;
    }

    /** Capture and validate the bounded declarations before observing or changing inventory. */
    private Map<Scope,List<RefillTarget>> targets(Map<String,List<RefillTarget>> supplied) {
        Objects.requireNonNull(supplied,"refillByGroup");
        if(supplied.size()>100)throw new IllegalArgumentException("at most 100 Groups");
        var captured=new LinkedHashMap<Scope,List<RefillTarget>>();
        int declarations=0;
        for(var group:supplied.entrySet()) {
            requireNonBlank(group.getKey(),"workerGroupId");
            var rows=Objects.requireNonNull(group.getValue(),"refill");
            if(rows.size()>10_000-declarations) throw new IllegalArgumentException("at most 10,000 declarations");
            declarations+=rows.size();
            for(var row:rows) {
                Objects.requireNonNull(row,"refill declaration");
                requirePoolPolicy(group.getKey(),row.poolName());
                captured.computeIfAbsent(new Scope(group.getKey(),row.poolName()),ignored->new ArrayList<>()).add(row);
            }
        }
        var result=new LinkedHashMap<Scope,List<RefillTarget>>();
        captured.forEach((scope,rows)->{
            var merged=new LinkedHashMap<EligibilityQuery,Integer>();
            var handler=requirePoolPolicy(scope.workerGroupId(),scope.poolName());
            rows.forEach(target->merged.merge(handler.normalizeQuery(scope.workerGroupId(),target.target()),
                    target.count(),Math::max));
            result.put(scope,merged.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e->e.getKey().toString()))
                    .map(e->new RefillTarget(scope.poolName(),e.getKey(),e.getValue())).toList());
        });
        return Collections.unmodifiableMap(result);
    }

    private record RefillPage(List<RefillTarget> targets,int nextCursor,int deficit) { }

    private @Nullable RefillPage page(Scope scope,List<RefillTarget> targets,PoolRefillPolicy handler) {
        int room=budget.available();
        if(room==0)return null;
        int start=Math.floorMod(targetPagesByScope.getOrDefault(scope,0),targets.size());
        for(int offset=0;offset<targets.size();offset+=100) {
            var selected=new ArrayList<RefillTarget>();
            for(int i=offset;i<Math.min(offset+100,targets.size());i++)
                selected.add(targets.get((start+i)%targets.size()));
            int missing=handler.deficits(scope.workerGroupId(),targetCounts(selected)).values()
                    .stream().mapToInt(Integer::intValue).sum();
            if(missing>0)return new RefillPage(List.copyOf(selected),
                    (start+offset+(targets.size()>100?selected.size():1))%targets.size(),Math.min(room,missing));
        }
        return null;
    }

    Map<String,Integer> observeRefillDeficits(Map<String,List<RefillTarget>> supplied) {
        var targets=targets(supplied);
        // Reclaim idle stock only when a requested Group-Pool cannot admit anything.
        if (targets.keySet().stream().anyMatch(scope -> {
            var pool = pools.get(scope.poolName());
            return pool != null && pool.remainingCapacity(scope.workerGroupId()) == 0;
        })) {
            pools.values().forEach(WorkerCandidatePool::discardExpired);
        }
        targetPagesByScope.keySet().retainAll(targets.keySet());
        poolRotationByGroup.keySet().retainAll(supplied.keySet());
        var deficits=new LinkedHashMap<String,Integer>();
        targets.forEach((scope,rows)->{
            var handler=requirePoolPolicy(scope.workerGroupId(),scope.poolName());
            if (handler.targetBatching() == PoolRefillPolicy.TargetBatching.ALL) {
                int missing=handler.deficits(scope.workerGroupId(),targetCounts(rows)).values().stream().mapToInt(Integer::intValue).sum();
                if(missing>0)deficits.merge(scope.workerGroupId(),Math.min(budget.available(),missing),Integer::sum);
            } else {
                var page=page(scope,rows,handler);
                if(page!=null)deficits.merge(scope.workerGroupId(),page.deficit(),Integer::sum);
            }
        });
        deficits.values().forEach(deficit->requestedDeficit+=Math.min(deficit,10_000));
        long now=clock.getAsLong();
        if(now-lastDiagnosticMillis>=60_000) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                    "Pool refill deficit="+requestedDeficit+" "+budget.diagnostics());
            lastDiagnosticMillis=now;
        }
        var result=new LinkedHashMap<String,Integer>();
        supplied.keySet().forEach(group->{
            int deficit=deficits.getOrDefault(group,0);
            if(deficit>0)result.put(group,deficit);
        });
        return Collections.unmodifiableMap(result);
    }

    int refill(String group,List<RefillTarget> declarations,
            Map<String, Long> offered) {
        requireNonBlank(group,"workerGroupId");
        Objects.requireNonNull(offered,"offeredCandidates");
        if(offered.size()>100)throw new IllegalArgumentException("at most 100 candidate Workers");
        var candidates=new LinkedHashMap<String,Long>();
        offered.forEach((id, score) -> {
            requireNonBlank(id,"Worker ID");
            if(score == null || score == 0)throw new IllegalArgumentException("strict candidate required");
            candidates.put(id,score);
        });
        return refill(group, targets(Map.of(group, declarations)), candidates);
    }

    private int refill(String group, Map<Scope, List<RefillTarget>> declaredTargets, Map<String, Long> candidates) {
        var scopes=declaredTargets.entrySet().stream()
                .sorted(java.util.Comparator.<Map.Entry<Scope,List<RefillTarget>>>comparingInt(
                        entry -> poolPositions.get(entry.getKey().poolName()))).toList();
        var remaining=new LinkedHashSet<>(candidates.keySet());
        if(remaining.isEmpty() || scopes.isEmpty())return 0;
        int start=Math.floorMod(poolRotationByGroup.getOrDefault(group,0),scopes.size());
        poolRotationByGroup.put(group,(start+1)%scopes.size());
        int added=0;
        for(int n=0;n<scopes.size() && !remaining.isEmpty() && added<100;n++) {
            var entry=scopes.get((start+n)%scopes.size());
            var scope=entry.getKey();
            var handler=requirePoolPolicy(group,scope.poolName());
            List<RefillTarget> selected;
            RefillPage page=null;
            if (handler.targetBatching() == PoolRefillPolicy.TargetBatching.ALL) {
                selected=entry.getValue();
            } else {
                page=page(scope,entry.getValue(),handler);
                if(page==null) {
                    int cursor=Math.floorMod(targetPagesByScope.getOrDefault(scope,0),entry.getValue().size());
                    var selectedRows=new ArrayList<RefillTarget>();
                    for(int i=0;i<Math.min(100,entry.getValue().size());i++)
                        selectedRows.add(entry.getValue().get((cursor+i)%entry.getValue().size()));
                    page=new RefillPage(List.copyOf(selectedRows),(cursor+selectedRows.size())%entry.getValue().size(),0);
                }
                selected=page.targets();
            }
            if(remaining.isEmpty())break;
            if(page!=null)targetPagesByScope.put(scope,page.nextCursor());
            // Each Pool commits its own admission. A later failure preserves earlier successes.
            var batch=new LinkedHashMap<String,Long>();
            remaining.forEach(id->batch.put(id,candidates.get(id)));
            var accepted=handler.refill(group,targetCounts(selected),Collections.unmodifiableMap(batch),100-added);
            if(accepted.size()>100-added || new LinkedHashSet<>(accepted).size()!=accepted.size() || !remaining.containsAll(accepted))
                throw new IllegalStateException("Pool policy returned invalid admitted identities");
            // Each newly candidateized generation can enter only one Pool in this supply batch.
            remaining.removeAll(accepted);
            added+=accepted.size();
        }
        return added;
    }

    private static Map<EligibilityQuery, Integer> targetCounts(List<RefillTarget> targets) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        targets.forEach(target -> result.merge(target.target(), target.count(), Math::max));
        return Collections.unmodifiableMap(result);
    }

    List<RefillTarget> normalizeRefill(String group, List<RefillTarget> declarations) {
        requireNonBlank(group,"workerGroupId"); Objects.requireNonNull(declarations,"refill");
        if (declarations.size()>100) throw new IllegalArgumentException("at most 100 refill declarations");
        return targets(Map.of(group,declarations)).values().stream().flatMap(List::stream).toList();
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

}
