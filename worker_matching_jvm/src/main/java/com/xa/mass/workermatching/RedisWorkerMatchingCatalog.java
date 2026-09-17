package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
import com.xa.mass.workermatching.rules.MatchingStorage;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import com.xa.mass.kernel.assignment.WorkerQuery;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
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
import tools.jackson.core.JacksonException;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Matching owns facts, Pool supply admission, and finite Group index projections. */
public final class RedisWorkerMatchingCatalog implements WorkerMatchingCatalog, AutoCloseable {
    private final MatchingStorage storage;
    private final ObjectMapper mapper=JsonMapper.builder().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();
    private final Map<String,PoolRefillPolicy> handlers;
    private final Map<String,QueryFunctions> executors;
    private final Map<String,MatchingGroup> groups;
    private final Map<String,List<MatchingStorage.IndexMutation>> indexesByGroup;
    private final Map<String,String> scriptsByGroup;
    private static final String NO_INDEX_SCRIPT=FactsIndexStore.script(List.of());
    private record Scope(String workerGroupId,String poolName) { }
    private final LongSupplier clock;
    private final Map<String,Integer> eligibilityCursors=new LinkedHashMap<>();
    private final Map<Scope,Integer> queryCursors=new LinkedHashMap<>();
    private long lastDiagnosticMillis;
    private long requestedDeficit;

    public RedisWorkerMatchingCatalog(MatchingStorage storage,Map<String,PoolRefillPolicy> poolPolicies,
            Map<String,QueryFunctions> queryFunctions,
            Map<String,MatchingGroup> groups,Map<String,List<MatchingStorage.IndexMutation>> indexesByGroup) {
        this.storage=Objects.requireNonNull(storage,"storage");
        this.clock=storage::now;
        this.handlers=Map.copyOf(poolPolicies);
        this.executors=Map.copyOf(queryFunctions);
        executors.keySet().forEach(id -> requireNonBlank(id,"executorName"));
        handlers.keySet().forEach(id->requireNonBlank(id,"Pool name"));
        var instances=Collections.newSetFromMap(new java.util.IdentityHashMap<PoolRefillPolicy,Boolean>());
        handlers.values().forEach(handler->{
            if(!instances.add(handler))throw new IllegalArgumentException("one Pool name per maintenance instance");
        });
        this.groups = Map.copyOf(groups);
        var indexes = new LinkedHashMap<String,List<MatchingStorage.IndexMutation>>();
        var scripts = new LinkedHashMap<String,String>();
        groups.forEach((group, configuration) -> {
            requireNonBlank(group, "WorkerGroup");
            for (String name : configuration.pools())
                if (!handlers.containsKey(name)) throw new IllegalArgumentException("Unknown Pool: " + name);
            for (String name : configuration.functions())
                if (!executors.containsKey(name)) throw new IllegalArgumentException("Unknown function: " + name);
        });
        indexesByGroup.forEach((group, mutations) -> {
            if (!groups.containsKey(group)) throw new IllegalArgumentException("Index Group unavailable");
            var unique = new LinkedHashMap<String,MatchingStorage.IndexMutation>();
            for (var mutation : mutations) {
                var prior = unique.putIfAbsent(mutation.namespace(), mutation);
                if (prior != null && !prior.equals(mutation)) throw new IllegalArgumentException("Conflicting index resource");
            }
            var captured = List.copyOf(unique.values());
            indexes.put(group, captured); scripts.put(group, FactsIndexStore.script(captured));
        });
        this.indexesByGroup = Map.copyOf(indexes); this.scriptsByGroup = Map.copyOf(scripts);
    }

    /** Startup only, before facts admission and Pacer start. Never scheduled in the background. */
    public void rebuildIndexes() {
        for (String group:indexesByGroup.keySet()) {
            if(indexesByGroup.get(group).isEmpty())continue;
            var redis=commands();
            ScanCursor cursor;
            for(var index:indexesByGroup.get(group)) {
                String root=indexBase(group)+":"+index.namespace();
                // The exact root and its descendants only; never another resource's index.
                redis.unlink(root);
                cursor=ScanCursor.INITIAL;
                do {
                    var page=redis.scan(cursor,new ScanArgs().match(root+":*").limit(100));
                    if(!page.getKeys().isEmpty())redis.unlink(page.getKeys().toArray(String[]::new));
                    cursor=page;
                } while(!cursor.isFinished());
            }
            cursor=ScanCursor.INITIAL;
            do {
                var page=redis.hscan(workerFactsKey(group),cursor,new ScanArgs().limit(100));
                var ids=new ArrayList<String>();
                for (var entry:page.getMap().entrySet()) {
                    decodeObject(entry.getValue()); ids.add(entry.getKey()); ids.add("{}");
                    if (ids.size()==200) { mutate(group,"rebuild",ids); ids.clear(); }
                }
                if (!ids.isEmpty()) mutate(group,"rebuild",ids);
                cursor=page;
            } while (!cursor.isFinished());
        }
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

    private QueryFunctions requireExecutor(String group, String name) {
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
        return new WorkerQuery(query.executorName(), function.normalizeInput().apply(group, query.input()));
    }

    @Override public Map<String,WorkerCandidate> take(String group, Map<String,WorkerQuery> queriesByMessageId) {
        requireNonBlank(group, "workerGroupId");
        Objects.requireNonNull(queriesByMessageId, "queriesByMessageId");
        if (queriesByMessageId.size() > 100) throw new IllegalArgumentException("at most 100 Item queries");
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
            var result = Objects.requireNonNull(executors.get(name).execute().apply(group, Collections.unmodifiableMap(inputs)), "executor result");
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
        int room=storage.availableCapacity();
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
        storage.expireCandidates();
        queryCursors.keySet().retainAll(targets.keySet());
        eligibilityCursors.keySet().retainAll(supplied.keySet());
        var deficits=new LinkedHashMap<String,Integer>();
        targets.forEach((scope,rows)->{
            var handler=requireEligibility(scope.workerGroupId(),scope.poolName());
            if (scope.poolName().equals("country")) {
                int missing=handler.deficits(scope.workerGroupId(),targetCounts(rows)).values().stream().mapToInt(Integer::intValue).sum();
                if(missing>0)deficits.merge(scope.workerGroupId(),Math.min(storage.availableCapacity(),missing),Integer::sum);
            } else {
                var page=page(scope,rows,handler);
                if(page!=null)deficits.merge(scope.workerGroupId(),page.deficit(),Integer::sum);
            }
        });
        deficits.values().forEach(deficit->requestedDeficit+=Math.min(deficit,10_000));
        long now=clock.getAsLong();
        if(now-lastDiagnosticMillis>=60_000) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                    "Eligibility refill deficit="+requestedDeficit+" "+storage.diagnostics());
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
        int room=Math.min(100,storage.availableCapacity()), added=0;
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

    private List<Long> mutate(String group,String mode,List<String> input) {
        var args=new ArrayList<String>(); args.add(mode); args.addAll(input);
        return commands().eval(scriptsByGroup.getOrDefault(group,NO_INDEX_SCRIPT),ScriptOutputType.MULTI,
                new String[]{workerFactsKey(group),workerPlatformFactsKey(group),indexBase(group)},args.toArray(String[]::new));
    }

    @Override public Map<String,MutationResult> upsertWorkerFactsBatch(String group,Map<String,Map<String,String>> facts) {
        requireNonBlank(group,"workerGroupId"); Objects.requireNonNull(facts,"facts");
        if (facts.isEmpty() || facts.size()>100) throw new IllegalArgumentException("Worker facts batch must contain 1..100 entries");
        var args=new ArrayList<String>(); var ids=new ArrayList<String>(); var result=new LinkedHashMap<String,MutationResult>();
        facts.forEach((id,properties) -> {
            requireNonBlank(id,"workerId");
            if (properties==null || ((Map<?,?>)properties).entrySet().stream().anyMatch(e -> !(e.getKey() instanceof String key) || key.isBlank() || !(e.getValue() instanceof String))) {
                result.put(id,result(MutationStatus.INVALID,"invalid Worker properties"));
            } else { ids.add(id); args.add(id); args.add(encodeObject(properties)); }
        });
        if (!args.isEmpty()) {
            var effects=mutate(group,"replace",args);
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
            encoded=encodeObject(properties);
        } catch (IllegalArgumentException invalid) { return result(MutationStatus.INVALID,"invalid platform properties"); }
        long effect=mutate(group,"patch",List.of(id,encoded)).getFirst();
        return new MutationResult(effect<0 ? MutationStatus.NOT_FOUND : effect==0 ? MutationStatus.UNCHANGED : MutationStatus.APPLIED);
    }

    @Override public List<RefillTarget> normalizeRefill(String group, List<RefillTarget> declarations) {
        requireNonBlank(group,"workerGroupId"); Objects.requireNonNull(declarations,"refill");
        if (declarations.size()>100) throw new IllegalArgumentException("at most 100 refill declarations");
        return targets(Map.of(group,declarations)).values().stream().flatMap(List::stream).toList();
    }

    private String indexBase(String group) { return storage.indexBase(group); }
    private String workerFactsKey(String group) { return storage.workerFactsKey(group); }
    private String workerPlatformFactsKey(String group) { return storage.base()+":matching:worker:platform-properties:"+group; }

    @Override
    public Map<String, @Nullable WorkerFacts> loadWorkerFacts(
            String workerGroupId,
            List<String> workerIds
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        List<String> ids = boundedUnique(workerIds, "workerIds");
        if (ids.isEmpty()) {
            return Map.of();
        }
        RedisCommands<String, String> commands = commands();
        List<KeyValue<String, String>> workers = commands.hmget(
                workerFactsKey(workerGroupId),
                ids.toArray(String[]::new)
        );
        List<KeyValue<String, String>> platforms = commands.hmget(
                workerPlatformFactsKey(workerGroupId),
                ids.toArray(String[]::new)
        );
        LinkedHashMap<String, WorkerFacts> result = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            String workerId = ids.get(index);
            String workerRaw = workers.get(index).getValueOrElse(null);
            if (workerRaw == null) {
                result.put(workerId, null);
                continue;
            }
            try {
                String platformRaw = platforms.get(index).getValueOrElse(null);
                result.put(workerId, new WorkerFacts(
                        workerId,
                        workerGroupId,
                        decodeObject(workerRaw),
                        platformRaw == null
                                ? Map.of()
                                : decodeObject(platformRaw)
                ));
            } catch (IllegalArgumentException error) {
                result.put(workerId, null);
            }
        }
        return immutableNullableMap(result);
    }

    private RedisCommands<String, String> commands() { return storage.commands(); }

    @Override public void close() {
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,"Eligibility stopped "+storage.diagnostics());
        storage.close();
    }

    private static MutationResult result(
            MutationStatus status,
            String reason
    ) {
        return new MutationResult(status, reason);
    }

    private String encodeObject(Map<String, ?> value) {
        try {
            return mapper.writeValueAsString(canonicalJsonValue(value));
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "value is not JSON-compatible",
                    error
            );
        }
    }

    private Map<String, Object> decodeObject(String raw) { return MatchingStorage.decodeObject(raw); }

    private static Object canonicalJsonValue(Object value) {
        if (value instanceof Map<?, ?> mapping) {
            TreeMap<String, Object> sorted = new TreeMap<>();
            mapping.forEach((key, item) -> {
                if (!(key instanceof String stringKey)) {
                    throw new IllegalArgumentException(
                            "JSON object keys must be strings"
                    );
                }
                sorted.put(stringKey, canonicalJsonValue(item));
            });
            return sorted;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> items = new ArrayList<>(collection.size());
            collection.forEach(item -> items.add(canonicalJsonValue(item)));
            return items;
        }
        return snapshotJsonValue(value);
    }

    private static Object snapshotJsonValue(Object value) {
        return canonicalJsonValueScalar(value);
    }

    private static Object canonicalJsonValueScalar(Object value) {
        if (value == null || value instanceof String
                || value instanceof Boolean) {
            return value;
        }
        if (value instanceof Double number && !Double.isFinite(number)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof Float number && !Float.isFinite(number)) {
            throw new IllegalArgumentException("JSON numbers must be finite");
        }
        if (value instanceof Number) {
            return value;
        }
        if (value instanceof Map<?, ?> || value instanceof Collection<?>) {
            return canonicalJsonValue(value);
        }
        throw new IllegalArgumentException("value is not JSON-compatible");
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
