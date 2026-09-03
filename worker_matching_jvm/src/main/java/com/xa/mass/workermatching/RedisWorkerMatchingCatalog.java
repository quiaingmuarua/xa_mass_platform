package com.xa.mass.workermatching;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
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

/** Redis persistence for Worker facts and PRECOMPUTED Candidate Rules. */
public final class RedisWorkerMatchingCatalog
        implements WorkerMatchingCatalog, AutoCloseable {

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
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerMatchingCatalog(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
    }

    @Override
    public MutationResult upsertWorkerFacts(
            String workerId,
            String workerGroupId,
            Map<String, Object> workerProperties
    ) {
        WorkerFacts facts = new WorkerFacts(
                workerId,
                workerGroupId,
                workerProperties,
                Map.of()
        );
        String encoded;
        try {
            encoded = encodeObject(facts.workerProperties());
        } catch (IllegalArgumentException error) {
            return result(MutationStatus.INVALID, "invalid Worker properties");
        }
        String key = workerFactsKey(workerGroupId);
        String current = commands().hget(key, workerId);
        if (encoded.equals(current)) {
            return new MutationResult(MutationStatus.UNCHANGED);
        }
        commands().hset(key, workerId, encoded);
        return new MutationResult(MutationStatus.APPLIED);
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
    public MutationResult createCandidateRule(
            String candidateId,
            String workerGroupId,
            Map<String, Object> allocationRule
    ) {
        CandidateRule rule;
        String encoded;
        try {
            rule = new CandidateRule(
                    candidateId,
                    workerGroupId,
                    allocationRule
            );
            encoded = encodeRule(rule.workerGroupId(), rule.allocationRule());
        } catch (IllegalArgumentException error) {
            return result(MutationStatus.INVALID, "invalid Candidate rule");
        }
        return createOnly(
                candidateRulesKey(),
                rule.candidateId(),
                encoded
        );
    }

    @Override
    public Map<String, @Nullable CandidateRule> loadCandidateRules(
            List<String> candidateIds
    ) {
        List<String> ids = boundedUnique(candidateIds, "candidateIds");
        if (ids.isEmpty()) {
            return Map.of();
        }
        List<KeyValue<String, String>> values = commands().hmget(
                candidateRulesKey(),
                ids.toArray(String[]::new)
        );
        LinkedHashMap<String, CandidateRule> result = new LinkedHashMap<>();
        for (int index = 0; index < ids.size(); index++) {
            String candidateId = ids.get(index);
            String raw = values.get(index).getValueOrElse(null);
            result.put(
                    candidateId,
                    raw == null
                            ? null
                            : decodeCandidateRule(candidateId, raw)
            );
        }
        return immutableNullableMap(result);
    }

    private MutationResult createOnly(
            String key,
            String field,
            String encoded
    ) {
        RedisCommands<String, String> commands = commands();
        if (commands.hsetnx(key, field, encoded)) {
            return new MutationResult(MutationStatus.APPLIED);
        }
        return encoded.equals(commands.hget(key, field))
                ? new MutationResult(MutationStatus.UNCHANGED)
                : result(
                        MutationStatus.CONFLICT,
                        "create-only value conflicts with stored value"
                );
    }

    private String workerFactsKey(String workerGroupId) {
        return keyspace.base() + ":matching:worker:facts:" + workerGroupId;
    }

    private String workerPlatformFactsKey(String workerGroupId) {
        return keyspace.base()
                + ":matching:worker:platform-properties:"
                + workerGroupId;
    }

    private String candidateRulesKey() {
        return keyspace.base() + ":matching:candidate:rules";
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

    private CandidateRule decodeCandidateRule(
            String candidateId,
            String raw
    ) {
        try {
            Map<String, Object> object = decodeObject(raw);
            requireExactFields(
                    object,
                    Set.of("workerGroupId", "allocationRule")
            );
            return new CandidateRule(
                    candidateId,
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
