package com.xa.mass.workermatching;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.task.TaskItemWorkerSelector;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HexFormat;
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

/** Redis persistence for Worker facts, shared Rules and Task bindings. */
public final class RedisWorkerMatchingCatalog
        implements WorkerMatchingCatalog, AutoCloseable {

    private static final String BIND_TASK_RULE_SCRIPT = """
            local binding = redis.call('HGET', KEYS[1], ARGV[1])
            local definition = nil
            if ARGV[4] ~= '' then definition = redis.call('HGET', KEYS[2], ARGV[3]) end
            if (binding and binding ~= ARGV[2]) or (definition and definition ~= ARGV[4]) then
              return -1
            end
            if binding and (definition or ARGV[4] == '') then return 0 end
            if not definition and ARGV[4] ~= '' then redis.call('HSET', KEYS[2], ARGV[3], ARGV[4]) end
            if not binding then redis.call('HSET', KEYS[1], ARGV[1], ARGV[2]) end
            return 1
            """;

    private static final String LOAD_TASK_RULES_SCRIPT = """
            local bindings = redis.call('HMGET', KEYS[1], unpack(ARGV))
            local ids, seen = {}, {}
            for _, raw in ipairs(bindings) do
              if raw then
                local ok,binding=pcall(cjson.decode,raw)
                local id=ok and type(binding)=='table' and binding.ruleId or nil
                if type(id)=='string' and not seen[id] then
                  seen[id]=true
                  ids[#ids+1]=id
                end
              end
            end
            local definitions = {}
            if #ids > 0 then definitions = redis.call('HMGET', KEYS[2], unpack(ids)) end
            return {bindings, ids, definitions}
            """;

    private static final String PATCH_PLATFORM_SCRIPT = """
            if redis.call('HEXISTS', KEYS[1], ARGV[1]) == 0 then
              return -1
            end
            local current = redis.call('HGET', KEYS[2], ARGV[1])
            if ARGV[2] == 'missing' then
              if current then return 0 end
            elseif not current or current ~= ARGV[3] then
              return 0
            end
            redis.call('HSET', KEYS[2], ARGV[1], ARGV[4])
            return 1
            """;

    private final RedisClient redisClient;
    private final ObjectMapper mapper = JsonMapper.builder()
            .enable(DeserializationFeature.USE_LONG_FOR_INTS)
            .build();
    private final RedisKeyspace keyspace;
    private final Set<String> countryIndexGroups;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerMatchingCatalog(
            RedisClient redisClient,
            RedisKeyspace keyspace,
            Set<String> countryIndexGroups
    ) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
        this.countryIndexGroups = Set.copyOf(countryIndexGroups);
        this.countryIndexGroups.forEach(group -> requireNonBlank(group, "country index Group"));
    }

    /** Startup only, before exposing this Catalog to producers or the Pacer. */
    public void rebuildCountryIndexes() {
        for (String group : countryIndexGroups) {
            RedisCommands<String, String> redis = commands();
            redis.del(countryIndexKey(group));
            ScanCursor cursor = ScanCursor.INITIAL;
            do {
                MapScanCursor<String, String> page = redis.hscan(workerFactsKey(group), cursor, new ScanArgs().limit(100));
                List<String> batch = new ArrayList<>();
                for (Map.Entry<String, String> entry : page.getMap().entrySet()) {
                    // Fail startup on corrupt facts, never install a partial silent interpretation.
                    decodeObject(entry.getValue());
                    batch.add(entry.getKey());
                    batch.add(entry.getValue());
                    if (batch.size() == 200) {
                        rebuildBatch(group, batch);
                        batch.clear();
                    }
                }
                if (!batch.isEmpty()) rebuildBatch(group, batch);
                cursor = page;
            } while (!cursor.isFinished());
        }
    }

    private void rebuildBatch(String group, List<String> batch) {
        commands().eval(CountryRuleHandler.REBUILD_INDEX_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{workerFactsKey(group), countryIndexKey(group)}, batch.toArray(String[]::new));
    }

    @Override
    public void validateWorkerSelector(String workerGroupId, TaskItemWorkerSelector selector) {
        requireCountryIndex(workerGroupId);
        CountryRuleHandler.validate(selector);
    }

    private void requireCountryIndex(String workerGroupId) {
        requireNonBlank(workerGroupId, "workerGroupId");
        if (!countryIndexGroups.contains(workerGroupId)) {
            throw new IllegalArgumentException("country index is not enabled for WorkerGroup");
        }
    }

    @Override
    public @Nullable TaskQuery prepareTaskQuery(String taskId, String workerGroupId) {
        requireNonBlank(taskId, "taskId");
        requireNonBlank(workerGroupId, "workerGroupId");
        String raw = commands().hmget(taskRulesKey(), taskId).getFirst().getValueOrElse(null);
        MatchingRule binding = decodeBinding(raw);
        if (binding == null || !workerGroupId.equals(binding.workerGroupId())
                || !CountryRuleHandler.ID.equals(binding.ruleId()) || !countryIndexGroups.contains(workerGroupId)) {
            return null;
        }
        return CountryRuleHandler.query(this::commands, countryIndexKey(workerGroupId));
    }

    @Override
    public List<String> takeWorkerIds(String workerGroupId, TaskItemWorkerSelector selector, int limit) {
        validateWorkerSelector(workerGroupId, selector);
        return CountryRuleHandler.query(this::commands, countryIndexKey(workerGroupId)).take(Map.of(selector, limit)).get(selector);
    }

    @Override
    public Set<String> retainWorkerIds(String workerGroupId, TaskItemWorkerSelector selector, List<String> workerIds) {
        validateWorkerSelector(workerGroupId, selector);
        return CountryRuleHandler.query(this::commands, countryIndexKey(workerGroupId)).retain(Map.of(selector, workerIds)).get(selector);
    }

    private String countryIndexKey(String group) {
        return keyspace.base() + ":matching:worker:index:country:" + group;
    }

    @Override
    public Map<String, MutationResult> upsertWorkerFactsBatch(
            String workerGroupId,
            Map<String, Map<String, String>> propertiesByWorkerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        Objects.requireNonNull(propertiesByWorkerId, "propertiesByWorkerId");
        if (propertiesByWorkerId.isEmpty() || propertiesByWorkerId.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("Worker facts batch must contain 1..100 entries");
        }
        propertiesByWorkerId.keySet().forEach(id -> requireNonBlank(id, "workerId"));
        Map<String, String> encoded = new LinkedHashMap<>();
        Map<String, MutationResult> results = new LinkedHashMap<>();
        propertiesByWorkerId.forEach((id, properties) -> {
            if (properties == null || ((Map<?, ?>) properties).entrySet().stream().anyMatch(
                    entry -> !(entry.getKey() instanceof String key) || key.isBlank()
                            || !(entry.getValue() instanceof String))) {
                results.put(id, result(MutationStatus.INVALID, "invalid Worker properties"));
            } else {
                encoded.put(id, encodeObject(properties));
            }
        });
        results.putAll(storeWorkerFacts(workerGroupId, encoded));
        return Collections.unmodifiableMap(results);
    }

    private Map<String, MutationResult> storeWorkerFacts(
            String workerGroupId,
            Map<String, String> encoded
    ) {
        if (encoded.isEmpty()) {
            return Map.of();
        }
        if (countryIndexGroups.contains(workerGroupId)) {
            List<Long> effects = CountryRuleHandler.replaceFacts(commands(), workerFactsKey(workerGroupId),
                    countryIndexKey(workerGroupId), encoded);
            Map<String, MutationResult> results = new LinkedHashMap<>();
            int i = 0;
            for (String id : encoded.keySet()) {
                results.put(id, new MutationResult(effects.get(i++) == 0 ? MutationStatus.UNCHANGED : MutationStatus.APPLIED));
            }
            return Collections.unmodifiableMap(results);
        }
        String key = workerFactsKey(workerGroupId);
        RedisCommands<String, String> commands = commands();
        List<KeyValue<String, String>> current = commands.hmget(key, encoded.keySet().toArray(String[]::new));
        Map<String, String> changed = new LinkedHashMap<>();
        Map<String, MutationResult> results = new LinkedHashMap<>();
        for (KeyValue<String, String> value : current) {
            String replacement = encoded.get(value.getKey());
            boolean unchanged = replacement.equals(value.getValueOrElse(null));
            results.put(value.getKey(), new MutationResult(
                    unchanged ? MutationStatus.UNCHANGED : MutationStatus.APPLIED
            ));
            if (!unchanged) {
                changed.put(value.getKey(), replacement);
            }
        }
        if (!changed.isEmpty()) {
            // Whole JSON values, no observation-order fence between batches.
            commands.hset(key, changed);
        }
        return Collections.unmodifiableMap(results);
    }

    @Override
    public MutationResult patchWorkerPlatformProperties(
            String workerGroupId,
            String workerId,
            Map<String, @Nullable Object> properties
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(workerId, "workerId");
        Objects.requireNonNull(properties, "properties");
        if (properties.keySet().stream().anyMatch(key ->
                key == null || key.isBlank())) {
            return result(
                    MutationStatus.INVALID,
                    "platform property names must be non-blank"
            );
        }
        RedisCommands<String, String> commands = commands();
        String factsKey = workerFactsKey(workerGroupId);
        String platformKey = workerPlatformFactsKey(workerGroupId);
        for (int attempt = 0; attempt < 8; attempt++) {
            String observed = commands.hget(platformKey, workerId);
            Map<String, Object> current;
            try {
                current = observed == null
                        ? new LinkedHashMap<>()
                        : new LinkedHashMap<>(decodeObject(observed));
                properties.forEach((name, value) -> {
                    if (value == null) {
                        current.remove(name);
                    } else {
                        current.put(name, snapshotJsonValue(value));
                    }
                });
            } catch (IllegalArgumentException error) {
                return result(
                        MutationStatus.INVALID,
                        "invalid platform properties"
                );
            }
            String replacement = encodeObject(current);
            if (replacement.equals(observed)
                    || observed == null && current.isEmpty()) {
                return commands.hexists(factsKey, workerId)
                        ? new MutationResult(MutationStatus.UNCHANGED)
                        : new MutationResult(MutationStatus.NOT_FOUND);
            }
            Number changed = commands.eval(
                    PATCH_PLATFORM_SCRIPT,
                    ScriptOutputType.INTEGER,
                    new String[]{factsKey, platformKey},
                    workerId,
                    observed == null ? "missing" : "present",
                    observed == null ? "" : observed,
                    replacement
            );
            if (changed != null && changed.longValue() == 1) {
                return new MutationResult(MutationStatus.APPLIED);
            }
            if (changed != null && changed.longValue() == -1) {
                return new MutationResult(MutationStatus.NOT_FOUND);
            }
        }
        return result(
                MutationStatus.CONFLICT,
                "platform properties changed concurrently"
        );
    }

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

    @Override
    public MutationResult bindTaskAllocationRule(
            String taskId,
            String workerGroupId,
            Map<String, Object> allocationRule
    ) {
        String encoded;
        try {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(workerGroupId, "workerGroupId");
            encoded = encodeRule(workerGroupId, requireObject(allocationRule));
        } catch (IllegalArgumentException error) {
            return result(MutationStatus.INVALID, "invalid Task rule binding");
        }
        return bind(taskId, workerGroupId, ruleId(encoded), encoded);
    }

    @Override
    public MutationResult bindTaskRule(String taskId, String workerGroupId, String ruleId) {
        try {
            requireNonBlank(taskId, "taskId");
            requireCountryIndex(workerGroupId);
            if (!CountryRuleHandler.ID.equals(ruleId)) throw new IllegalArgumentException("unknown Rule");
        } catch (IllegalArgumentException error) {
            return result(MutationStatus.INVALID, "unknown Rule or unavailable Group index");
        }
        return bind(taskId, workerGroupId, ruleId, "");
    }

    private MutationResult bind(String taskId, String workerGroupId, String id, String definition) {
        String binding = encodeObject(Map.of("workerGroupId", workerGroupId, "ruleId", id));
        long effect = commands().eval(BIND_TASK_RULE_SCRIPT, ScriptOutputType.INTEGER,
                new String[]{taskRulesKey(), rulesKey()}, taskId, binding, id, definition);
        return switch ((int) effect) {
            case 1 -> new MutationResult(MutationStatus.APPLIED);
            case 0 -> new MutationResult(MutationStatus.UNCHANGED);
            default -> result(MutationStatus.CONFLICT, "Task binding or shared Rule conflicts with stored value");
        };
    }

    @Override
    public Map<String, @Nullable MatchingRule> loadTaskRules(
            List<String> taskIds
    ) {
        Objects.requireNonNull(taskIds, "taskIds");
        List<String> ids = boundedUnique(new ArrayList<>(new LinkedHashSet<>(taskIds)), "taskIds");
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<?> rows = commands().eval(LOAD_TASK_RULES_SCRIPT, ScriptOutputType.MULTI,
                new String[]{taskRulesKey(), rulesKey()}, ids.toArray(String[]::new));
        List<?> bindings = (List<?>) rows.get(0);
        List<?> ruleIds = (List<?>) rows.get(1);
        List<?> definitions = (List<?>) rows.get(2);
        Map<String, MatchingRule> rules = new LinkedHashMap<>();
        for (int index = 0; index < ruleIds.size(); index++) {
            String id = (String) ruleIds.get(index);
            String raw = (String) definitions.get(index);
            rules.put(id, raw == null ? null : decodeMatchingRule(id, raw));
        }
        LinkedHashMap<String, MatchingRule> result = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            MatchingRule binding = decodeBinding((String) bindings.get(index));
            MatchingRule rule = binding == null ? null : rules.get(binding.ruleId());
            if (binding != null && CountryRuleHandler.ID.equals(binding.ruleId())) {
                rule = binding;
            }
            result.put(ids.get(index), binding != null && rule != null
                    && binding.workerGroupId().equals(rule.workerGroupId()) ? rule : null);
        }
        return immutableNullableMap(result);
    }

    private static String ruleId(String encoded) {
        try {
            return "rule-" + HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(encoded.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private String workerFactsKey(String workerGroupId) {
        return keyspace.base() + ":matching:worker:facts:" + workerGroupId;
    }

    private String workerPlatformFactsKey(String workerGroupId) {
        return keyspace.base()
                + ":matching:worker:platform-properties:"
                + workerGroupId;
    }

    private String rulesKey() {
        return keyspace.base() + ":matching:candidate:rules";
    }

    private String taskRulesKey() {
        return keyspace.base() + ":matching:task:rules";
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

    private String encodeRule(
            String workerGroupId,
            Map<String, Object> allocationRule
    ) {
        return encodeObject(Map.of(
                "workerGroupId", workerGroupId,
                "allocationRule", allocationRule
        ));
    }

    private @Nullable MatchingRule decodeBinding(@Nullable String raw) {
        if (raw == null) return null;
        try {
            Map<String, Object> object = decodeObject(raw);
            requireExactFields(object, Set.of("ruleId", "workerGroupId"));
            return new MatchingRule(requireString(object.get("ruleId")), requireString(object.get("workerGroupId")), null);
        } catch (IllegalArgumentException error) {
            return null;
        }
    }

    private MatchingRule decodeMatchingRule(
            String id,
            String raw
    ) {
        try {
            if (!ruleId(raw).equals(id)) return null;
            Map<String, Object> object = decodeObject(raw);
            requireExactFields(
                    object,
                    Set.of("workerGroupId", "allocationRule")
            );
            return new MatchingRule(
                    id,
                    requireString(object.get("workerGroupId")),
                    requireObject(object.get("allocationRule"))
            );
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
