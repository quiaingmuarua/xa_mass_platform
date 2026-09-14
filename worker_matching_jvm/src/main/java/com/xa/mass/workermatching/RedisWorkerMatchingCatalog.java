package com.xa.mass.workermatching;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
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
    private final RedisClient redisClient;
    private final ObjectMapper mapper=JsonMapper.builder().enable(DeserializationFeature.USE_LONG_FOR_INTS).build();
    private final RedisKeyspace keyspace;
    private final Map<String,RuleHandler> handlers;
    private final Map<String,Set<String>> rulesByGroup;
    private final Map<String,List<RuleHandler.IndexMutation>> indexesByGroup;
    private final Map<String,String> scriptsByGroup;
    private static final String NO_INDEX_SCRIPT=FactsIndexStore.script(List.of());
    private final SharedEligibilityInventory inventory = new SharedEligibilityInventory();
    private final Map<String,Map<String,List<EligibilityQuery>>> defaultTargets;
    private final Map<String,Integer> eligibilityCursors=new LinkedHashMap<>();
    private final Map<SharedEligibilityInventory.Scope,Integer> queryCursors=new LinkedHashMap<>();
    private long lastDiagnosticMillis;
    private long requestedDeficit;
    private volatile StatefulRedisConnection<String,String> connection;

    public RedisWorkerMatchingCatalog(RedisClient client,RedisKeyspace keyspace,Map<String,RuleHandler> ruleHandlers,Map<String,Set<String>> groupRules,
                                      Map<String,Map<String,List<EligibilityQuery>>> defaultTargets) {
        this.redisClient=Objects.requireNonNull(client,"redisClient"); this.keyspace=Objects.requireNonNull(keyspace,"keyspace");
        this.handlers=Map.copyOf(ruleHandlers);
        if(!handlers.containsKey(DEFAULT_RULE_ID))throw new IllegalArgumentException("worker.default Handler is required");
        var namespaces=new LinkedHashSet<String>();
        handlers.forEach((id,handler)->{
            requireNonBlank(id,"Rule ID");
            for(var index:handler.indexes())if(!namespaces.add(index.namespace()))
                throw new IllegalArgumentException("Conflicting Rule index namespace: "+index.namespace());
        });
        var groups=new LinkedHashMap<String,Set<String>>();
        var indexes=new LinkedHashMap<String,List<RuleHandler.IndexMutation>>();
        var scripts=new LinkedHashMap<String,String>();
        groupRules.forEach((group,ids)->{
            requireNonBlank(group,"WorkerGroup");
            var enabled=Set.copyOf(ids);
            var mutations=new ArrayList<RuleHandler.IndexMutation>();
            for(String id:enabled) {
                var handler=handlers.get(id);
                if(handler==null)throw new IllegalArgumentException("Unknown Rule: "+id);
                mutations.addAll(handler.indexes());
            }
            groups.put(group,enabled); indexes.put(group,List.copyOf(mutations));
            scripts.put(group,FactsIndexStore.script(mutations));
        });
        rulesByGroup=Map.copyOf(groups); indexesByGroup=Map.copyOf(indexes); scriptsByGroup=Map.copyOf(scripts);
        var defaults=new LinkedHashMap<String,Map<String,List<EligibilityQuery>>>();
        defaultTargets.forEach((group,rules) -> {
            requireNonBlank(group,"WorkerGroup");
            var targets=new LinkedHashMap<String,List<EligibilityQuery>>();
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

    private @Nullable SharedEligibility eligibility(String group,String id) {
        var handler=handlers.get(id);
        var enabled=rulesByGroup.getOrDefault(group,Set.of());
        if(handler==null || !DEFAULT_RULE_ID.equals(id) && !enabled.contains(id))return null;
        return new SharedEligibility(inventory,new SharedEligibilityInventory.Scope(group,id),
                handler.bind(this::commands,indexBase(group),enabled));
    }

    private final class BindingView implements TaskQuery {
        final TaskRuleBinding binding;
        final SharedEligibility eligibility;
        BindingView(TaskRuleBinding binding, SharedEligibility eligibility) {
            this.binding=binding; this.eligibility=Objects.requireNonNull(eligibility);
        }
        @Override public void validate(TaskItemWorkerSelector selector) {
            eligibility.selector(selector.expression(),1);
        }
        @Override public Map<TaskItemWorkerSelector,List<HeldCandidate>> take(Map<TaskItemWorkerSelector,Integer> limits) {
            if (limits.size()>100) throw new IllegalArgumentException("at most 100 selectors");
            var normalized=new LinkedHashMap<TaskItemWorkerSelector,EligibilityQuery>();
            var counts=new LinkedHashMap<Map<String,List<String>>,Integer>();
            limits.forEach((selector,count) -> {
                EligibilityQuery query=eligibility.selector(selector.expression(),count);
                normalized.put(selector,query);
                counts.merge(query.query(),count,Integer::sum);
            });
            var queries=counts.entrySet().stream().map(e -> new EligibilityQuery(e.getKey(),e.getValue())).toList();
            var taken=eligibility.take(queries);
            var offsets=new LinkedHashMap<Map<String,List<String>>,Integer>();
            var result=new LinkedHashMap<TaskItemWorkerSelector,List<HeldCandidate>>();
            normalized.forEach((selector,query) -> {
                var workers=taken.get(new EligibilityQuery(query.query(),counts.get(query.query())));
                int from=offsets.getOrDefault(query.query(),0), to=Math.min(workers.size(),from+query.count());
                result.put(selector,List.copyOf(workers.subList(from,to))); offsets.put(query.query(),to);
            });
            return Collections.unmodifiableMap(result);
        }
    }

    private Map<SharedEligibilityInventory.Scope,List<EligibilityQuery>> targets(Map<String,@Nullable TaskQuery> tasks) {
        if (tasks.size()>100) throw new IllegalArgumentException("at most 100 prepared Tasks");
        var targets=new LinkedHashMap<SharedEligibilityInventory.Scope,LinkedHashMap<Map<String,List<String>>,Integer>>();
        for (TaskQuery task:tasks.values()) {
            if (task==null) continue;
            if (!(task instanceof RedisWorkerMatchingCatalog.BindingView view)) throw new IllegalArgumentException("foreign Task query");
            var binding=view.binding;
            var scope=new SharedEligibilityInventory.Scope(binding.workerGroupId(),binding.ruleId());
            var merged=targets.computeIfAbsent(scope,ignored -> new LinkedHashMap<>());
            binding.refillTargets().forEach(q -> merged.merge(q.query(),q.count(),Math::max));
        }
        queryCursors.keySet().retainAll(targets.keySet());
        var groups=new LinkedHashSet<String>();
        targets.keySet().forEach(scope->groups.add(scope.workerGroupId()));
        eligibilityCursors.keySet().retainAll(groups);
        var result=new LinkedHashMap<SharedEligibilityInventory.Scope,List<EligibilityQuery>>();
        targets.forEach((scope,queries)->result.put(scope,queries.entrySet().stream()
                .sorted(java.util.Comparator.comparing(e->e.getKey().toString()))
                .map(e->new EligibilityQuery(e.getKey(),e.getValue())).toList()));
        return result;
    }

    private record RefillPage(List<EligibilityQuery> queries,int nextCursor,int deficit) { }

    private @Nullable RefillPage page(SharedEligibilityInventory.Scope scope,SharedEligibility index,List<EligibilityQuery> queries) {
        int room=index.room();
        if(room==0)return null;
        int start=Math.floorMod(queryCursors.getOrDefault(scope,0),queries.size());
        for(int offset=0;offset<queries.size();offset+=100) {
            var selected=new ArrayList<EligibilityQuery>();
            for(int i=offset;i<Math.min(offset+100,queries.size());i++)selected.add(queries.get((start+i)%queries.size()));
            int missing=index.deficits(selected).values().stream().mapToInt(Integer::intValue).sum();
            if(missing>0)return new RefillPage(List.copyOf(selected),
                    (start+offset+(queries.size()>100?selected.size():1))%queries.size(),Math.min(room,missing));
        }
        return null;
    }

    @Override public Map<String,Integer> deficits(Map<String,@Nullable TaskQuery> tasks) {
        var result=new LinkedHashMap<String,Integer>();
        targets(tasks).forEach((scope,queries)->{
            var page=page(scope,eligibility(scope.workerGroupId(),scope.ruleId()),queries);
            if(page!=null)result.merge(scope.workerGroupId(),page.deficit(),Integer::sum);
        });
        result.replaceAll((group,count)->Math.min(count,SharedEligibilityInventory.PROCESS_CAPACITY));
        requestedDeficit+=result.values().stream().mapToInt(Integer::intValue).sum();
        return Collections.unmodifiableMap(result);
    }

    @Override public int refill(Map<String,@Nullable TaskQuery> tasks,String group,
            List<HeldCandidate> offered,CandidateRenewal renewal) {
        requireNonBlank(group,"WorkerGroup");
        Objects.requireNonNull(renewal,"renewal");
        if(offered.size()>100)throw new IllegalArgumentException("at most 100 offered Workers");
        var remaining=new LinkedHashMap<String,HeldCandidate>();
        var unique=new LinkedHashSet<String>();
        long now=System.currentTimeMillis();
        for(var candidate:offered) {
            requireNonBlank(candidate.workerId(),"Worker ID");
            if(!unique.add(candidate.workerId()))throw new IllegalArgumentException("offered Workers must be unique");
            if(candidate.expiresAtMillis()>now)remaining.put(candidate.workerId(),candidate);
        }
        inventory.recordHeld(offered.size());
        var targets=targets(tasks);
        var scopes=targets.keySet().stream().filter(scope->scope.workerGroupId().equals(group))
                .sorted(java.util.Comparator.comparing(SharedEligibilityInventory.Scope::ruleId)).toList();
        if(scopes.isEmpty() || remaining.isEmpty())return 0;
        int start=Math.floorMod(eligibilityCursors.getOrDefault(group,0),scopes.size());
        eligibilityCursors.put(group,(start+1)%scopes.size());
        int room=inventory.availableCapacity();
        var planned=new LinkedHashMap<SharedEligibilityInventory.Scope,List<SharedEligibilityInventory.Entry>>();
        var selected=new LinkedHashMap<String,SharedEligibilityInventory.Entry>();
        for(int n=0;n<scopes.size() && !remaining.isEmpty() && selected.size()<room;n++) {
            var scope=scopes.get((start+n)%scopes.size());
            var index=eligibility(group,scope.ruleId());
            var page=page(scope,index,targets.get(scope));
            if(page==null)continue;
            // Advance before fallible Handler work, including batches matching no Worker.
            queryCursors.put(scope,page.nextCursor());
            var entries=index.select(page.queries(),List.copyOf(remaining.values()),room-selected.size());
            planned.put(scope,entries);
            entries.forEach(entry->{ selected.put(entry.held().workerId(),entry); remaining.remove(entry.held().workerId()); });
        }
        if(selected.isEmpty())return 0;
        var renewed=new LinkedHashMap<String,HeldCandidate>();
        for(var candidate:renewal.renew(List.copyOf(selected.keySet()))) {
            var original=selected.get(candidate.workerId());
            if(original==null || renewed.putIfAbsent(candidate.workerId(),candidate)!=null
                    || candidate.score()==original.held().score())throw new IllegalStateException("renewal must return new fences for a subset of the admission plan");
        }
        inventory.recordRenewal(selected.size(),renewed.size());
        int added=0;
        for(var entry:planned.entrySet()) {
            var entries=new ArrayList<SharedEligibilityInventory.Entry>();
            for(var original:entry.getValue()) {
                var candidate=renewed.get(original.held().workerId());
                if(candidate!=null)entries.add(new SharedEligibilityInventory.Entry(candidate,original.member().projection()));
            }
            added+=inventory.add(entry.getKey(),entries);
        }
        now=System.currentTimeMillis();
        if (now-lastDiagnosticMillis>=60_000) {
            System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,
                    "Eligibility refill deficit="+requestedDeficit+" "+inventory.diagnostics());
            lastDiagnosticMillis=now;
        }
        return added;
    }

    private List<EligibilityQuery> resolveTargets(String group,String rule,List<EligibilityQuery> targets) {
        var index=eligibility(group,rule);
        if (index==null) throw new IllegalArgumentException("unavailable Rule");
        if (targets.isEmpty() || targets.size()>100) throw new IllegalArgumentException("refillTargets requires 1..100 queries");
        var merged=new LinkedHashMap<Map<String,List<String>>,Integer>();
        targets.forEach(target -> { var normalized=index.normalize(target.query(),target.count());
            merged.merge(normalized.query(),normalized.count(),Math::max); });
        return merged.entrySet().stream().map(e -> new EligibilityQuery(e.getKey(),e.getValue())).toList();
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

    @Override public MutationResult bindTaskRule(String taskId,String group,String ruleId,@Nullable List<EligibilityQuery> refillTargets) {
        List<EligibilityQuery> targets;
        try {
            requireNonBlank(taskId,"taskId"); requireNonBlank(group,"workerGroupId"); requireNonBlank(ruleId,"ruleId");
            targets=resolveTargets(group,ruleId,refillTargets!=null ? refillTargets
                    : defaultTargets.getOrDefault(group,Map.of()).getOrDefault(ruleId,List.of(new EligibilityQuery(Map.of(),100))));
        } catch (IllegalArgumentException invalid) { return result(MutationStatus.INVALID,"unknown Rule or unavailable Group index"); }
        String binding=encodeObject(Map.of("workerGroupId",group,"ruleId",ruleId,"refillTargets",targets.stream()
                .map(q -> Map.of("query",q.query(),"count",q.count())).toList()));
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

    private String indexBase(String group) { return keyspace.base()+":matching:worker:index:"+java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(group.getBytes(StandardCharsets.UTF_8)); }
    private String workerFactsKey(String group) { return keyspace.base()+":matching:worker:facts:"+group; }
    private String workerPlatformFactsKey(String group) { return keyspace.base()+":matching:worker:platform-properties:"+group; }
    private String taskRulesKey() { return keyspace.base()+":matching:task:rules"; }

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

    private RedisCommands<String, String> commands() {
        return connection().sync();
    }

    private StatefulRedisConnection<String, String> connection() {
        StatefulRedisConnection<String, String> current = connection;
        if (current == null || !current.isOpen()) {
            synchronized (this) {
                current = connection;
                if (current == null || !current.isOpen()) {
                    current = redisClient.connect(StringCodec.UTF8);
                    connection = current;
                }
            }
        }
        return current;
    }

    @Override
    public void close() {
        System.getLogger(getClass().getName()).log(System.Logger.Level.INFO,"Eligibility stopped "+inventory.diagnostics());
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
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
            var targets=new ArrayList<EligibilityQuery>();
            for (Object row:rows) {
                var target=requireObject(row);
                requireExactFields(target,Set.of("query","count"));
                if (!(target.get("count") instanceof Long count) || count<1 || count>1000) throw new IllegalArgumentException("invalid count");
                var query=new LinkedHashMap<String,List<String>>();
                requireObject(target.get("query")).forEach((key,value) -> {
                    if (!(value instanceof List<?> values)) throw new IllegalArgumentException("invalid query");
                    query.put(key,values.stream().map(RedisWorkerMatchingCatalog::requireString).toList());
                });
                targets.add(new EligibilityQuery(query,count.intValue()));
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
