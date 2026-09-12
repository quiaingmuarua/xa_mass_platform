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
    private final Map<String,Set<RuleHandler>> handlersByGroup;
    private final Map<String,String> scriptsByGroup;
    private static final String NO_INDEX_SCRIPT=FactsIndexStore.script(Set.of());
    private volatile StatefulRedisConnection<String,String> connection;

    public RedisWorkerMatchingCatalog(RedisClient client,RedisKeyspace keyspace,Map<String,Set<String>> groupRules) {
        this.redisClient=Objects.requireNonNull(client,"redisClient"); this.keyspace=Objects.requireNonNull(keyspace,"keyspace");
        var handlers=new LinkedHashMap<String,Set<RuleHandler>>();
        groupRules.forEach((group,ids) -> {
            requireNonBlank(group,"WorkerGroup");
            var enabled=java.util.EnumSet.noneOf(RuleHandler.class);
            ids.forEach(id -> enabled.add(RuleHandler.named(id)));
            handlers.put(group,Collections.unmodifiableSet(enabled));
        });
        handlersByGroup=Map.copyOf(handlers);
        var scripts=new LinkedHashMap<String,String>();
        handlers.forEach((group,enabled) -> scripts.put(group,FactsIndexStore.script(enabled)));
        scriptsByGroup=Map.copyOf(scripts);
    }

    /** Startup only, before facts admission and Pacer start. Never scheduled in the background. */
    public void rebuildIndexes() {
        for (String group:handlersByGroup.keySet()) {
            var redis=commands();
            ScanCursor cursor=ScanCursor.INITIAL;
            do {
                var page=redis.scan(cursor,new ScanArgs().match(indexBase(group)+":*").limit(100));
                if (!page.getKeys().isEmpty()) redis.unlink(page.getKeys().toArray(String[]::new));
                cursor=page;
            } while (!cursor.isFinished());
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
            result.put(task,binding!=null && group.equals(binding.workerGroupId()) ? query(group,binding.ruleId()) : null);
        });
        return immutableNullableMap(result);
    }

    private @Nullable TaskQuery query(String group,String id) {
        if (DEFAULT_RULE_ID.equals(id)) return new TaskQuery() {
            private TaskQuery propertyQuery() {
                var query=RedisWorkerMatchingCatalog.this.query(group,RuleHandler.COUNTRY.id);
                if (query==null) throw new IllegalArgumentException("country index unavailable");
                return query;
            }
            @Override public boolean usesIdentitySelection(TaskItemWorkerSelector selector) {
                return selector.isAny() || selector.hasExplicitWorkerIds();
            }
            @Override public void validate(TaskItemWorkerSelector selector) {
                if (!usesIdentitySelection(selector)) propertyQuery().validate(selector);
            }
            @Override public Map<TaskItemWorkerSelector,List<String>> take(Map<TaskItemWorkerSelector,Integer> limits) {
                return propertyQuery().take(limits);
            }
            @Override public Map<TaskItemWorkerSelector,Set<String>> retain(Map<TaskItemWorkerSelector,List<String>> held) {
                return propertyQuery().retain(held);
            }
        };
        RuleHandler handler;
        try { handler=RuleHandler.named(id); } catch (IllegalArgumentException unknown) { return null; }
        if (!handlersByGroup.getOrDefault(group,Set.of()).contains(handler)) return null;
        return RuleIndex.query(this::commands,indexBase(group)+":"+handler.indexName,handler::criteria);
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

    @Override public MutationResult bindTaskRule(String taskId,String group,String ruleId) {
        try {
            requireNonBlank(taskId,"taskId"); requireNonBlank(group,"workerGroupId"); requireNonBlank(ruleId,"ruleId");
            if (query(group,ruleId)==null) throw new IllegalArgumentException("unavailable Rule");
        } catch (IllegalArgumentException invalid) { return result(MutationStatus.INVALID,"unknown Rule or unavailable Group index"); }
        String binding=encodeObject(Map.of("workerGroupId",group,"ruleId",ruleId));
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
            result.put(row.getKey(),binding!=null && query(binding.workerGroupId(),binding.ruleId())!=null ? binding : null);
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
            requireExactFields(object, Set.of("ruleId", "workerGroupId"));
            return new TaskRuleBinding(requireString(object.get("ruleId")), requireString(object.get("workerGroupId")));
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
