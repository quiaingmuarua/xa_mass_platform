package com.xa.mass.workermatching;

import com.xa.mass.workermatching.rules.RedisRuleStorage;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.function.LongSupplier;
import org.jspecify.annotations.Nullable;
import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** Matching owns facts, immutable Task bindings, and the finite Group index projections. */
public final class RedisWorkerMatchingCatalog implements WorkerMatchingCatalog, AutoCloseable {
    private static final String BIND = """
            local old=redis.call('HGET',KEYS[1],ARGV[1])
            if old then return old==ARGV[2] and 0 or -1 end
            redis.call('HSET',KEYS[1],ARGV[1],ARGV[2]); return 1
            """;
    private final RedisRuleStorage storage;
    private final ObjectMapper mapper=JsonMapper.builder().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();
    private final Map<String,RuleHandler> handlers;
    private final Map<String,Set<String>> rulesByGroup;
    private final Map<String,List<RedisRuleStorage.IndexMutation>> indexesByGroup;
    private final Map<String,String> scriptsByGroup;
    private static final String NO_INDEX_SCRIPT=FactsIndexStore.script(List.of());
    private record Scope(String workerGroupId,String ruleId) { }
    private final LongSupplier clock;
    private final Map<String,Map<String,List<RefillTarget>>> defaultTargets;
    private final Map<String,Integer> eligibilityCursors=new LinkedHashMap<>();
    private final Map<Scope,Integer> queryCursors=new LinkedHashMap<>();
    private long lastDiagnosticMillis;
    private long requestedDeficit;

    public RedisWorkerMatchingCatalog(RedisRuleStorage storage,Map<String,RuleHandler> ruleHandlers,
            Map<String,Set<String>> groupRules,Map<String,Map<String,List<RefillTarget>>> defaultTargets) {
        this.storage=Objects.requireNonNull(storage,"storage");
        this.clock=storage::now;
        this.handlers=Map.copyOf(ruleHandlers);
        if(!handlers.containsKey(DEFAULT_RULE_ID))throw new IllegalArgumentException("worker.default Handler is required");
        handlers.keySet().forEach(id->requireNonBlank(id,"Rule ID"));
        var instances=Collections.newSetFromMap(new java.util.IdentityHashMap<RuleHandler,Boolean>());
        handlers.values().forEach(handler->{
            if(!instances.add(handler))throw new IllegalArgumentException("one Rule ID per Handler instance");
        });
        var groups=new LinkedHashMap<String,Set<String>>();
        var indexes=new LinkedHashMap<String,List<RedisRuleStorage.IndexMutation>>();
        var scripts=new LinkedHashMap<String,String>();
        groupRules.forEach((group,ids)->{
            requireNonBlank(group,"WorkerGroup");
            var enabled=Set.copyOf(ids);
            var mutations=new ArrayList<RedisRuleStorage.IndexMutation>();
            for(String id:enabled) {
                var handler=handlers.get(id);
                if(handler==null)throw new IllegalArgumentException("Unknown Rule: "+id);
                mutations.addAll(storage.indexes(id));
            }
            groups.put(group,enabled); indexes.put(group,List.copyOf(mutations));
            scripts.put(group,FactsIndexStore.script(mutations));
        });
        rulesByGroup=Map.copyOf(groups); indexesByGroup=Map.copyOf(indexes); scriptsByGroup=Map.copyOf(scripts);
        var defaults=new LinkedHashMap<String,Map<String,List<RefillTarget>>>();
        defaultTargets.forEach((group,rules) -> {
            requireNonBlank(group,"WorkerGroup");
            var targets=new LinkedHashMap<String,List<RefillTarget>>();
            rules.forEach((rule,queries) -> targets.put(rule,resolveTargets(group,rule,queries)));
            defaults.put(group,Map.copyOf(targets));
        });
        this.defaultTargets=Map.copyOf(defaults);
    }

    /** Startup only, before facts admission and Pacer start. Never scheduled in the background. */
    public void rebuildIndexes() {
        for (String group:rulesByGroup.keySet()) {
            if(indexesByGroup.get(group).isEmpty())continue;
            var redis=commands();
            ScanCursor cursor;
            for(var index:indexesByGroup.get(group)) {
                String root=indexBase(group)+":"+index.namespace();
                // The exact root and its descendants only; never another Rule's index.
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

    @Override public Map<String,@Nullable TaskQuery> prepareTaskQueries(Map<String,String> taskGroups) {
        Objects.requireNonNull(taskGroups,"taskGroups");
        if (taskGroups.size()>MAX_BATCH_SIZE) throw new IllegalArgumentException("at most 100 Tasks");
        taskGroups.forEach((id,group) -> { requireNonBlank(id,"taskId"); requireNonBlank(group,"workerGroupId"); });
        var bindings=loadTaskBindings(List.copyOf(taskGroups.keySet()));
        Map<String,TaskQuery> result=new LinkedHashMap<>();
        taskGroups.forEach((task,group) -> {
            var binding=bindings.get(task);
            result.put(task,binding!=null && group.equals(binding.workerGroupId()) ? new BindingView(binding, eligibility(group,binding.ruleId())) : null);
        });
        return immutableNullableMap(result);
    }

    private @Nullable RuleHandler eligibility(String group,String id) {
        var handler=handlers.get(id);
        var enabled=rulesByGroup.getOrDefault(group,Set.of());
        return handler==null || !DEFAULT_RULE_ID.equals(id) && !enabled.contains(id) ? null : handler;
    }

    private final class BindingView implements TaskQuery {
        final TaskRuleBinding binding;
        final RuleHandler eligibility;
        BindingView(TaskRuleBinding binding, RuleHandler eligibility) {
            this.binding=binding; this.eligibility=Objects.requireNonNull(eligibility);
        }
        @Override public EligibilityQuery normalize(EligibilityQuery query) {
            return eligibility.normalizeQuery(binding.workerGroupId(),query);
        }
        @Override public Map<EligibilityQuery,List<HeldCandidate>> take(Map<EligibilityQuery,Integer> limits) {
            return eligibility.take(binding.workerGroupId(),limits);
        }
    }

    private Map<Scope,List<RefillTarget>> targets(Map<String,@Nullable TaskQuery> tasks) {
        if (tasks.size()>100) throw new IllegalArgumentException("at most 100 prepared Tasks");
        var targets=new LinkedHashMap<Scope,LinkedHashMap<EligibilityQuery,Integer>>();
        for (TaskQuery task:tasks.values()) {
            if (task==null) continue;
            if (!(task instanceof RedisWorkerMatchingCatalog.BindingView view)) throw new IllegalArgumentException("foreign Task query");
            var binding=view.binding;
            var scope=new Scope(binding.workerGroupId(),binding.ruleId());
            var merged=targets.computeIfAbsent(scope,ignored -> new LinkedHashMap<>());
            binding.refillTargets().forEach(q -> merged.merge(q.query(),q.count(),Math::max));
        }
        queryCursors.keySet().retainAll(targets.keySet());
        var groups=new LinkedHashSet<String>();
        targets.keySet().forEach(scope->groups.add(scope.workerGroupId()));
        eligibilityCursors.keySet().retainAll(groups);
        var result=new LinkedHashMap<Scope,List<RefillTarget>>();
        targets.forEach((scope,queries)->result.put(scope,queries.entrySet().stream()
                .sorted(java.util.Comparator.comparing(e->e.getKey().toString()))
                .map(e->RefillTarget.of(e.getKey(),e.getValue())).toList()));
        return result;
    }

    private record RefillPage(List<RefillTarget> queries,int nextCursor,int deficit) { }

    /** Invocation-local target paging; Rule representations never cross this boundary. */
    private final class PreparedEligibility {
        final Scope scope;
        final RuleHandler index;
        final List<RefillTarget> targets;

        PreparedEligibility(Scope scope,List<RefillTarget> targets) {
            this.scope=scope;
            this.index=Objects.requireNonNull(eligibility(scope.workerGroupId(),scope.ruleId()));
            this.targets=targets;
        }

        @Nullable RefillPage page() {
            int room=storage.availableCapacity();
            if(room==0)return null;
            int start=Math.floorMod(queryCursors.getOrDefault(scope,0),targets.size());
            for(int offset=0;offset<targets.size();offset+=100) {
                var selected=new ArrayList<RefillTarget>();
                for(int i=offset;i<Math.min(offset+100,targets.size());i++) {
                    var target=targets.get((start+i)%targets.size());
                    selected.add(target);
                }
                int missing=index.deficits(scope.workerGroupId(),targetCounts(selected)).values().stream().mapToInt(Integer::intValue).sum();
                if(missing>0)return new RefillPage(List.copyOf(selected),
                        (start+offset+(targets.size()>100?selected.size():1))%targets.size(),Math.min(room,missing));
            }
            return null;
        }
    }

    @Override public RefillBatch prepareRefill(Map<String,@Nullable TaskQuery> tasks) {
        Objects.requireNonNull(tasks,"preparedTasks");
        var targets=targets(tasks);
        storage.expireCandidates();
        var groups=new LinkedHashMap<String,List<PreparedEligibility>>();
        targets.forEach((scope,queries)->groups.computeIfAbsent(scope.workerGroupId(),ignored->new ArrayList<>())
                .add(new PreparedEligibility(scope,queries)));
        var needed=new LinkedHashSet<String>();
        groups.forEach((group,scopes)->{
            scopes.sort(java.util.Comparator.comparing(entry->entry.scope.ruleId()));
            int deficit=0;
            for(var scope:scopes) {
                var page=scope.page();
                if(page!=null)deficit+=page.deficit();
            }
            if(deficit>0)needed.add(group);
            requestedDeficit+=Math.min(deficit,10_000);
        });
        var neededGroups=Collections.unmodifiableSet(needed);
        long now=clock.getAsLong();
        if (now-lastDiagnosticMillis>=60_000) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                    "Eligibility refill deficit="+requestedDeficit+" "+storage.diagnostics());
            lastDiagnosticMillis=now;
        }
        return new RefillBatch() {
            @Override public Set<String> groupsNeedingRefill() { return neededGroups; }

            @Override public int refill(String group,List<HeldCandidate> offered) {
                var scopes=groups.get(group);
                if(scopes==null)throw new IllegalArgumentException("WorkerGroup is outside this refill batch");
                return refillGroup(scopes,group,offered);
            }
        };
    }

    private int refillGroup(List<PreparedEligibility> scopes,String group,
            List<HeldCandidate> offered) {
        requireNonBlank(group,"WorkerGroup");
        Objects.requireNonNull(offered,"offeredCandidates");
        if(offered.size()>100)throw new IllegalArgumentException("at most 100 held Workers");
        var held=new LinkedHashMap<String,HeldCandidate>();
        for(var candidate:offered) {
            Objects.requireNonNull(candidate,"heldCandidate"); requireNonBlank(candidate.workerId(),"Worker ID");
            if(held.putIfAbsent(candidate.workerId(),candidate)!=null)throw new IllegalArgumentException("held Worker IDs must be unique");
        }
        var remaining=new LinkedHashSet<>(held.keySet());
        if(scopes.isEmpty() || remaining.isEmpty())return 0;
        int start=Math.floorMod(eligibilityCursors.getOrDefault(group,0),scopes.size());
        eligibilityCursors.put(group,(start+1)%scopes.size());
        int room=Math.min(100,storage.availableCapacity()), added=0;
        for(int n=0;n<scopes.size() && !remaining.isEmpty() && added<room;n++) {
            var prepared=scopes.get((start+n)%scopes.size());
            var page=prepared.page();
            if(page==null)continue;
            long now=clock.getAsLong();
            remaining.removeIf(id->held.get(id).expiresAtMillis()<=now);
            if(remaining.isEmpty())break;
            queryCursors.put(prepared.scope,page.nextCursor());
            // Each Rule commits its own admission. A later failure preserves earlier successes.
            var accepted=prepared.index.refill(group,targetCounts(page.queries()),remaining.stream().map(held::get).toList(),room-added);
            if(accepted.size()>room-added || new LinkedHashSet<>(accepted).size()!=accepted.size() || !remaining.containsAll(accepted))
                throw new IllegalStateException("Rule returned invalid admitted identities");
            remaining.removeAll(accepted); added+=accepted.size();
        }
        return added;
    }

    private static Map<EligibilityQuery, Integer> targetCounts(List<RefillTarget> targets) {
        var result = new LinkedHashMap<EligibilityQuery, Integer>();
        targets.forEach(target -> result.merge(target.query(), target.count(), Math::max));
        return Collections.unmodifiableMap(result);
    }

    private List<RefillTarget> resolveTargets(String group,String rule,List<RefillTarget> targets) {
        var index=eligibility(group,rule);
        if (index==null) throw new IllegalArgumentException("unavailable Rule");
        if (targets.isEmpty() || targets.size()>100) throw new IllegalArgumentException("refillTargets requires 1..100 queries");
        var merged=new LinkedHashMap<EligibilityQuery,Integer>();
        targets.forEach(target -> { var normalized=index.normalizeQuery(group,target.query());
            merged.merge(normalized,target.count(),Math::max); });
        return merged.entrySet().stream().map(e -> RefillTarget.of(e.getKey(),e.getValue())).toList();
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

    @Override public MutationResult bindTaskRule(String taskId,String group,String ruleId,@Nullable List<RefillTarget> refillTargets) {
        List<RefillTarget> targets;
        try {
            requireNonBlank(taskId,"taskId"); requireNonBlank(group,"workerGroupId"); requireNonBlank(ruleId,"ruleId");
            targets=resolveTargets(group,ruleId,refillTargets!=null ? refillTargets
                    : defaultTargets.getOrDefault(group,Map.of()).getOrDefault(ruleId,List.of(new RefillTarget(Map.of(),100))));
        } catch (IllegalArgumentException invalid) { return result(MutationStatus.INVALID,"unknown Rule or unavailable Group index"); }
        String binding=encodeObject(Map.of("workerGroupId",group,"ruleId",ruleId,"refillTargets",targets.stream()
                .map(q -> Map.of("query",q.query().query(),"count",q.count())).toList()));
        long effect=commands().eval(BIND,ScriptOutputType.INTEGER,new String[]{taskRulesKey()},taskId,binding);
        return effect<0 ? result(MutationStatus.CONFLICT,"Task binding conflicts with stored value")
                : new MutationResult(effect==0 ? MutationStatus.UNCHANGED : MutationStatus.APPLIED);
    }

    @Override public Map<String,@Nullable TaskRuleBinding> loadTaskBindings(List<String> taskIds) {
        var ids=boundedUnique(taskIds,"taskIds"); if (ids.isEmpty()) return Map.of();
        var rows=commands().hmget(taskRulesKey(),ids.toArray(String[]::new));
        var result=new LinkedHashMap<String,TaskRuleBinding>();
        for (var row:rows) {
            var binding=decodeBinding(row.getValueOrElse(null));
            result.put(row.getKey(),binding!=null && eligibility(binding.workerGroupId(),binding.ruleId())!=null ? binding : null);
        }
        return immutableNullableMap(result);
    }

    private String indexBase(String group) { return storage.indexBase(group); }
    private String workerFactsKey(String group) { return storage.base()+":matching:worker:facts:"+group; }
    private String workerPlatformFactsKey(String group) { return storage.base()+":matching:worker:platform-properties:"+group; }
    private String taskRulesKey() { return storage.base()+":matching:task:rules"; }

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

    private @Nullable TaskRuleBinding decodeBinding(@Nullable String raw) {
        if (raw == null) return null;
        try {
            Map<String, Object> object = decodeObject(raw);
            requireExactFields(object, Set.of("ruleId", "workerGroupId", "refillTargets"));
            String rule=requireString(object.get("ruleId")), group=requireString(object.get("workerGroupId"));
            if (!(object.get("refillTargets") instanceof List<?> rows)) throw new IllegalArgumentException("invalid targets");
            var targets=new ArrayList<RefillTarget>();
            for (Object row:rows) {
                var target=requireObject(row);
                requireExactFields(target,Set.of("query","count"));
                if (!(target.get("count") instanceof Long count) || count<1 || count>1000) throw new IllegalArgumentException("invalid count");
                var query=new LinkedHashMap<String,List<String>>();
                requireObject(target.get("query")).forEach((key,value) -> {
                    if (!(value instanceof List<?> values)) throw new IllegalArgumentException("invalid query");
                    query.put(key,values.stream().map(RedisWorkerMatchingCatalog::requireString).toList());
                });
                targets.add(new RefillTarget(query,count.intValue()));
            }
            return new TaskRuleBinding(rule,group,resolveTargets(group,rule,targets));
        } catch (IllegalArgumentException error) {
            return null;
        }
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

    private Map<String, Object> decodeObject(String raw) {
        try {
            return requireObject(mapper.readValue(
                    raw,
                    new TypeReference<Map<String, Object>>() {
                    }
            ));
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "stored JSON is malformed",
                    error
            );
        }
    }

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

    private static Map<String, Object> requireObject(Object value) {
        if (!(value instanceof Map<?, ?> mapping)) {
            throw new IllegalArgumentException("value must be an object");
        }
        LinkedHashMap<String, Object> result = new LinkedHashMap<>();
        mapping.forEach((key, item) -> {
            if (!(key instanceof String stringKey)) {
                throw new IllegalArgumentException(
                        "JSON object keys must be strings"
                );
            }
            result.put(stringKey, item);
        });
        return Collections.unmodifiableMap(result);
    }

    private static String requireString(Object value) {
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("value must be non-blank text");
        }
        return text;
    }

    private static void requireExactFields(
            Map<String, Object> object,
            Set<String> fields
    ) {
        if (!object.keySet().equals(fields)) {
            throw new IllegalArgumentException("stored fields are invalid");
        }
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
