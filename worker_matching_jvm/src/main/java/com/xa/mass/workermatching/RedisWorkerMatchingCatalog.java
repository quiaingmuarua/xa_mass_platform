package com.xa.mass.workermatching;

import com.xa.mass.kernel.assignment.RefillTarget;
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

/** Matching owns facts, named Rule admission, and finite Group index projections. */
public final class RedisWorkerMatchingCatalog implements WorkerMatchingCatalog, AutoCloseable {
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

    private @Nullable RuleHandler eligibility(String group,String id) {
        var handler=handlers.get(id);
        var enabled=rulesByGroup.getOrDefault(group,Set.of());
        return handler==null || !DEFAULT_RULE_ID.equals(id) && !enabled.contains(id) ? null : handler;
    }

    private RuleHandler requireEligibility(String group,String ruleId) {
        requireNonBlank(group,"workerGroupId"); requireNonBlank(ruleId,"ruleId");
        var handler=eligibility(group,ruleId);
        if(handler==null)throw new IllegalArgumentException("unavailable Rule");
        return handler;
    }

    @Override public EligibilityQuery normalizeQuery(String group,String ruleId,EligibilityQuery query) {
        return requireEligibility(group,ruleId).normalizeQuery(group,Objects.requireNonNull(query,"query"));
    }

    @Override public Map<String,HeldCandidate> take(String group,String ruleId,
            Map<String,EligibilityQuery> queriesByMessageId) {
        var handler=requireEligibility(group,ruleId);
        Objects.requireNonNull(queriesByMessageId,"queriesByMessageId");
        if(queriesByMessageId.size()>100)throw new IllegalArgumentException("at most 100 Item queries");
        var captured=new LinkedHashMap<String,EligibilityQuery>();
        queriesByMessageId.forEach((id,query)->{
            requireNonBlank(id,"messageId");
            captured.put(id,Objects.requireNonNull(query,"query"));
        });
        if(captured.isEmpty())return Map.of();

        // All admission must finish before the single destructive Rule operation.
        var groups=new LinkedHashMap<EligibilityQuery,List<String>>();
        captured.forEach((id,query)->groups.computeIfAbsent(handler.normalizeQuery(group,query),
                ignored->new ArrayList<>()).add(id));
        var limits=new LinkedHashMap<EligibilityQuery,Integer>();
        groups.forEach((query,ids)->limits.put(query,ids.size()));
        var taken=handler.take(group,limits);
        var assigned=new LinkedHashMap<String,HeldCandidate>();
        groups.forEach((query,ids)->{
            var candidates=taken.getOrDefault(query,List.of());
            for(int i=0;i<Math.min(ids.size(),candidates.size());i++)assigned.put(ids.get(i),candidates.get(i));
        });
        var result=new LinkedHashMap<String,HeldCandidate>();
        captured.keySet().forEach(id->{
            var candidate=assigned.get(id);
            if(candidate!=null)result.put(id,candidate);
        });
        return Collections.unmodifiableMap(result);
    }

    /** Capture and validate the bounded declarations before observing or changing inventory. */
    private Map<Scope,List<RefillTarget>> targets(Map<String,Map<String,List<RefillTarget>>> supplied) {
        Objects.requireNonNull(supplied,"targetsByGroup");
        if(supplied.size()>100)throw new IllegalArgumentException("at most 100 Group/Rule coordinates");
        var captured=new LinkedHashMap<Scope,List<RefillTarget>>();
        int declarations=0;
        for(var group:supplied.entrySet()) {
            requireNonBlank(group.getKey(),"workerGroupId");
            var rules=Objects.requireNonNull(group.getValue(),"targetsByRule");
            if(rules.isEmpty() || rules.size()>100-captured.size())
                throw new IllegalArgumentException("requires 1..100 Group/Rule coordinates");
            for(var rule:rules.entrySet()) {
                requireEligibility(group.getKey(),rule.getKey());
                var rows=Objects.requireNonNull(rule.getValue(),"refillTargets");
                if(rows.isEmpty() || rows.size()>10_000-declarations)
                    throw new IllegalArgumentException("requires nonempty targets and at most 10,000 declarations");
                declarations+=rows.size();
                captured.put(new Scope(group.getKey(),rule.getKey()),List.copyOf(rows));
            }
        }
        var result=new LinkedHashMap<Scope,List<RefillTarget>>();
        captured.forEach((scope,rows)->{
            var merged=new LinkedHashMap<EligibilityQuery,Integer>();
            var handler=requireEligibility(scope.workerGroupId(),scope.ruleId());
            rows.forEach(target->merged.merge(handler.normalizeQuery(scope.workerGroupId(),target.query()),
                    target.count(),Math::max));
            result.put(scope,merged.entrySet().stream()
                    .sorted(java.util.Comparator.comparing(e->e.getKey().toString()))
                    .map(e->RefillTarget.of(e.getKey(),e.getValue())).toList());
        });
        return Collections.unmodifiableMap(result);
    }

    private record RefillPage(List<RefillTarget> queries,int nextCursor,int deficit) { }

    private @Nullable RefillPage page(Scope scope,List<RefillTarget> targets,RuleHandler handler) {
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

    @Override public Set<String> groupsNeedingRefill(Map<String,Map<String,List<RefillTarget>>> supplied) {
        var targets=targets(supplied);
        storage.expireCandidates();
        queryCursors.keySet().retainAll(targets.keySet());
        eligibilityCursors.keySet().retainAll(supplied.keySet());
        var deficits=new LinkedHashMap<String,Integer>();
        targets.forEach((scope,rows)->{
            var page=page(scope,rows,requireEligibility(scope.workerGroupId(),scope.ruleId()));
            if(page!=null)deficits.merge(scope.workerGroupId(),page.deficit(),Integer::sum);
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

    @Override public int refill(String group,Map<String,List<RefillTarget>> targetsByRule,
            List<HeldCandidate> offered) {
        requireNonBlank(group,"workerGroupId");
        Objects.requireNonNull(offered,"offeredCandidates");
        if(offered.size()>100)throw new IllegalArgumentException("at most 100 held Workers");
        var held=new LinkedHashMap<String,HeldCandidate>();
        for(var candidate:offered) {
            Objects.requireNonNull(candidate,"heldCandidate"); requireNonBlank(candidate.workerId(),"Worker ID");
            if(held.putIfAbsent(candidate.workerId(),candidate)!=null)throw new IllegalArgumentException("held Worker IDs must be unique");
        }
        var scopes=targets(Map.of(group,targetsByRule)).entrySet().stream()
                .sorted(java.util.Comparator.comparing(entry->entry.getKey().ruleId())).toList();
        var remaining=new LinkedHashSet<>(held.keySet());
        if(remaining.isEmpty())return 0;
        int start=Math.floorMod(eligibilityCursors.getOrDefault(group,0),scopes.size());
        eligibilityCursors.put(group,(start+1)%scopes.size());
        int room=Math.min(100,storage.availableCapacity()), added=0;
        for(int n=0;n<scopes.size() && !remaining.isEmpty() && added<room;n++) {
            var entry=scopes.get((start+n)%scopes.size());
            var scope=entry.getKey();
            var handler=requireEligibility(group,scope.ruleId());
            var page=page(scope,entry.getValue(),handler);
            if(page==null)continue;
            long now=clock.getAsLong();
            remaining.removeIf(id->held.get(id).expiresAtMillis()<=now);
            if(remaining.isEmpty())break;
            queryCursors.put(scope,page.nextCursor());
            // Each Rule commits its own admission. A later failure preserves earlier successes.
            var accepted=handler.refill(group,targetCounts(page.queries()),
                    remaining.stream().map(held::get).toList(),room-added);
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

    @Override public List<RefillTarget> resolveRefillTargets(String group, String ruleId,
            @Nullable List<RefillTarget> requested) {
        requireNonBlank(group, "workerGroupId");
        requireNonBlank(ruleId, "ruleId");
        return resolveTargets(group, ruleId, requested != null ? requested
                : defaultTargets.getOrDefault(group, Map.of()).getOrDefault(ruleId,
                        List.of(new RefillTarget(Map.of(), 100))));
    }

    private String indexBase(String group) { return storage.indexBase(group); }
    private String workerFactsKey(String group) { return storage.base()+":matching:worker:facts:"+group; }
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
