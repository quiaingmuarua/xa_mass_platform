package com.xa.mass.workermatching;

import com.xa.mass.workermatching.storage.FactsIndexStore;
import com.xa.mass.workermatching.pool.CandidateBudget;
import com.xa.mass.workermatching.pool.CandidatePool;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerQuery;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;

/** Matching owns facts, Pool supply admission, and finite Group index projections. */
public final class RedisWorkerMatchingCatalog implements WorkerMatchingCatalog, AutoCloseable {
    private static final int MAX_TAKE_REQUESTS = 100;

    private final FactsIndexStore storage;
    private final CandidateBudget budget;
    private final Map<String, CandidatePool> pools;
    private final Map<String,PoolRefillPolicy> handlers;
    private final Map<String,QueryFunction> executors;
    private final Map<String,MatchingGroup> groups;
    private record Scope(String workerGroupId,String poolName) { }
    private final LongSupplier clock;
    private final Map<String,Integer> eligibilityCursors=new LinkedHashMap<>();
    private final Map<Scope,Integer> queryCursors=new LinkedHashMap<>();
    private long lastDiagnosticMillis;
    private long requestedDeficit;

    public RedisWorkerMatchingCatalog(FactsIndexStore storage, CandidateBudget budget,
            Map<String, CandidatePool> pools, LongSupplier clock,
            Map<String,PoolRefillPolicy> poolPolicies, Map<String,QueryFunction> queryFunctions,
            Map<String,MatchingGroup> groups) {
        this.storage=Objects.requireNonNull(storage,"storage");
        this.budget=Objects.requireNonNull(budget,"budget");
        this.pools=Map.copyOf(pools);
        this.clock=Objects.requireNonNull(clock,"clock");
        this.handlers=Map.copyOf(poolPolicies);
        this.executors=Map.copyOf(queryFunctions);
        executors.keySet().forEach(id -> requireNonBlank(id,"executorName"));
        handlers.keySet().forEach(id->requireNonBlank(id,"Pool name"));
        var instances=Collections.newSetFromMap(new java.util.IdentityHashMap<PoolRefillPolicy,Boolean>());
        handlers.values().forEach(handler->{
            if(!instances.add(handler))throw new IllegalArgumentException("one Pool name per maintenance instance");
        });
        this.groups = Map.copyOf(groups);
        groups.forEach((group, configuration) -> {
            requireNonBlank(group, "WorkerGroup");
            for (String name : configuration.pools())
                if (!handlers.containsKey(name)) throw new IllegalArgumentException("Unknown Pool: " + name);
            for (String name : configuration.functions())
                if (!executors.containsKey(name)) throw new IllegalArgumentException("Unknown function: " + name);
        });
        if (!groups.keySet().containsAll(storage.indexedGroups()))
            throw new IllegalArgumentException("Index Group unavailable");
    }

    private @Nullable PoolRefillPolicy eligibility(String group,String id) {
        var handler=handlers.get(id);
        var enabled=groups.getOrDefault(group,new MatchingGroup(Set.of(),Set.of())).pools();
        return handler==null || !enabled.contains(id) ? null : handler;
    }

    private PoolRefillPolicy requireEligibility(String group,String poolName) {
        requireNonBlank(group,"workerGroupId"); requireNonBlank(poolName,"poolName");
        var handler=eligibility(group,poolName);
        if(handler==null)throw new IllegalArgumentException("unavailable Pool");
        return handler;
    }

    private QueryFunction requireExecutor(String group, String name) {
        requireNonBlank(group, "workerGroupId"); requireNonBlank(name, "executorName");
        var executor = executors.get(name);
        if (executor == null || !"workerId".equals(name)
                && !groups.getOrDefault(group, new MatchingGroup(Set.of(),Set.of())).functions().contains(name))
            throw new IllegalArgumentException("unavailable Matching function");
        return executor;
    }

    @Override public WorkerQuery normalizeQuery(String group, WorkerQuery query) {
        Objects.requireNonNull(query, "query");
        var function = requireExecutor(group, query.executorName());
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
            var result = Objects.requireNonNull(executors.get(name).apply(group, Collections.unmodifiableMap(inputs)), "executor result");
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
                requireEligibility(group.getKey(),row.poolName());
                captured.computeIfAbsent(new Scope(group.getKey(),row.poolName()),ignored->new ArrayList<>()).add(row);
            }
        }
        var result=new LinkedHashMap<Scope,List<RefillTarget>>();
        captured.forEach((scope,rows)->{
            var merged=new LinkedHashMap<EligibilityQuery,Integer>();
            var handler=requireEligibility(scope.workerGroupId(),scope.poolName());
            rows.forEach(target->merged.merge(handler.normalizeQuery(scope.workerGroupId(),target.target()),
                    target.count(),Math::max));
            result.put(scope,merged.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e->e.getKey().toString()))
                    .map(e->new RefillTarget(scope.poolName(),e.getKey(),e.getValue())).toList());
        });
        return Collections.unmodifiableMap(result);
    }

    private record RefillPage(List<RefillTarget> queries,int nextCursor,int deficit) { }

    private @Nullable RefillPage page(Scope scope,List<RefillTarget> targets,PoolRefillPolicy handler) {
        int room=budget.available();
        if(room==0)return null;
        int start=Math.floorMod(queryCursors.getOrDefault(scope,0),targets.size());
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

    @Override public Set<String> groupsNeedingRefill(Map<String,List<RefillTarget>> supplied) {
        var targets=targets(supplied);
        pools.values().forEach(CandidatePool::expireAll);
        queryCursors.keySet().retainAll(targets.keySet());
        eligibilityCursors.keySet().retainAll(supplied.keySet());
        var deficits=new LinkedHashMap<String,Integer>();
        targets.forEach((scope,rows)->{
            var handler=requireEligibility(scope.workerGroupId(),scope.poolName());
            if (scope.poolName().equals("country")) {
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
                    "Eligibility refill deficit="+requestedDeficit+" "+budget.diagnostics());
            lastDiagnosticMillis=now;
        }
        return Collections.unmodifiableSet(new LinkedHashSet<>(deficits.keySet()));
    }

    @Override public int refill(String group,List<RefillTarget> declarations,
            List<HeldCandidate> offered) {
        requireNonBlank(group,"workerGroupId");
        Objects.requireNonNull(offered,"offeredCandidates");
        if(offered.size()>100)throw new IllegalArgumentException("at most 100 held Workers");
        var held=new LinkedHashMap<String,HeldCandidate>();
        for(var candidate:offered) {
            Objects.requireNonNull(candidate,"heldCandidate"); requireNonBlank(candidate.workerId(),"Worker ID");
            if(held.putIfAbsent(candidate.workerId(),candidate)!=null)throw new IllegalArgumentException("held Worker IDs must be unique");
        }
        var scopes=targets(Map.of(group,declarations)).entrySet().stream()
                .sorted(java.util.Comparator.<Map.Entry<Scope,List<RefillTarget>>>comparingInt(
                        entry -> poolOrder(entry.getKey().poolName()))
                        .thenComparing(entry -> entry.getKey().poolName())).toList();
        var remaining=new LinkedHashSet<>(held.keySet());
        if(remaining.isEmpty() || scopes.isEmpty())return 0;
        int start=Math.floorMod(eligibilityCursors.getOrDefault(group,0),scopes.size());
        eligibilityCursors.put(group,(start+1)%scopes.size());
        int room=Math.min(100,budget.available()), added=0;
        for(int n=0;n<scopes.size() && !remaining.isEmpty() && added<room;n++) {
            var entry=scopes.get((start+n)%scopes.size());
            var scope=entry.getKey();
            var handler=requireEligibility(group,scope.poolName());
            List<RefillTarget> selected;
            RefillPage page=null;
            if (scope.poolName().equals("country")) {
                selected=entry.getValue();
            } else {
                page=page(scope,entry.getValue(),handler);
                if(page==null)continue;
                selected=page.queries();
            }
            long now=clock.getAsLong();
            remaining.removeIf(id->held.get(id).expiresAtMillis()<=now);
            if(remaining.isEmpty())break;
            if(page!=null)queryCursors.put(scope,page.nextCursor());
            // Each Pool commits its own admission. A later failure preserves earlier successes.
            var accepted=handler.refill(group,targetCounts(selected),
                    remaining.stream().map(held::get).toList(),room-added);
            if(accepted.size()>room-added || new LinkedHashSet<>(accepted).size()!=accepted.size() || !remaining.containsAll(accepted))
                throw new IllegalStateException("Pool policy returned invalid admitted identities");
            remaining.removeAll(accepted); added+=accepted.size();
        }
        return added;
    }

    private static Map<EligibilityQuery, Integer> targetCounts(List<RefillTarget> targets) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        targets.forEach(target -> result.merge(target.target(), target.count(), Math::max));
        return Collections.unmodifiableMap(result);
    }

    /** Preserve the fixed maintenance rotation independently of consumer function names. */
    private static int poolOrder(String name) {
        return switch (name) {
            case "proof-facts" -> 0;
            case "country" -> 1;
            case "any" -> 2;
            case "messaging" -> 3;
            default -> 4;
        };
    }

    @Override public Map<String,MutationResult> upsertWorkerFactsBatch(String group,Map<String,Map<String,String>> facts) {
        requireNonBlank(group,"workerGroupId"); Objects.requireNonNull(facts,"facts");
        if (facts.isEmpty() || facts.size()>100) throw new IllegalArgumentException("Worker facts batch must contain 1..100 entries");
        var args=new ArrayList<String>(); var ids=new ArrayList<String>(); var result=new LinkedHashMap<String,MutationResult>();
        facts.forEach((id,properties) -> {
            requireNonBlank(id,"workerId");
            if (properties==null || ((Map<?,?>)properties).entrySet().stream().anyMatch(e -> !(e.getKey() instanceof String key) || key.isBlank() || !(e.getValue() instanceof String))) {
                result.put(id,result(MutationStatus.INVALID,"invalid Worker properties"));
            } else { ids.add(id); args.add(id); args.add(FactsIndexStore.encodeObject(properties)); }
        });
        if (!args.isEmpty()) {
            var effects=storage.replaceWorkerFacts(group,args);
            for (int i=0;i<ids.size();i++) result.put(ids.get(i),new MutationResult(effects.get(i)==0 ? MutationStatus.UNCHANGED : MutationStatus.APPLIED));
        }
        var ordered=new LinkedHashMap<String,MutationResult>(); facts.keySet().forEach(id -> ordered.put(id,result.get(id)));
        return Collections.unmodifiableMap(ordered);
    }

    @Override public MutationResult patchWorkerPlatformProperties(String group,String id,Map<String,@Nullable Object> properties) {
        requireNonBlank(group,"workerGroupId"); requireNonBlank(id,"workerId"); Objects.requireNonNull(properties,"properties");
        String encoded;
        try {
            if (properties.size()>100 || properties.keySet().stream().anyMatch(key -> key==null || key.isBlank())) throw new IllegalArgumentException("invalid property names");
            encoded=FactsIndexStore.encodeObject(properties);
        } catch (IllegalArgumentException invalid) { return result(MutationStatus.INVALID,"invalid platform properties"); }
        long effect=storage.patchPlatformProperties(group,id,encoded);
        return new MutationResult(effect<0 ? MutationStatus.NOT_FOUND : effect==0 ? MutationStatus.UNCHANGED : MutationStatus.APPLIED);
    }

    @Override public List<RefillTarget> normalizeRefill(String group, List<RefillTarget> declarations) {
        requireNonBlank(group,"workerGroupId"); Objects.requireNonNull(declarations,"refill");
        if (declarations.size()>100) throw new IllegalArgumentException("at most 100 refill declarations");
        return targets(Map.of(group,declarations)).values().stream().flatMap(List::stream).toList();
    }

    @Override public Map<String, @Nullable WorkerFacts> loadWorkerFacts(String group, List<String> workerIds) {
        requireNonBlank(group, "workerGroupId");
        return storage.loadWorkerFacts(group, boundedUnique(workerIds, "workerIds"));
    }

    @Override public void close() {
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,"Eligibility stopped "+budget.diagnostics());
        storage.close();
    }

    private static MutationResult result(
            MutationStatus status,
            String reason
    ) {
        return new MutationResult(status, reason);
    }

    private static List<String> boundedUnique(
            List<String> values,
            String name
    ) {
        Objects.requireNonNull(values, name);
        if (values.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    name + " must contain at most " + MAX_BATCH_SIZE + " entries"
            );
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String value : values) {
            requireNonBlank(value, name + " entry");
            if (!unique.add(value)) {
                throw new IllegalArgumentException(
                        name + " must not contain duplicates"
                );
            }
        }
        return List.copyOf(unique);
    }

    private static <K, V> Map<K, V> immutableNullableMap(Map<K, V> source) {
        return Collections.unmodifiableMap(new LinkedHashMap<>(source));
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

}
