package com.xa.mass.workermatching.index;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;

/** Persistent Group-local value-to-identity lookup. Facts commits maintain the HASH. */
public final class RedisHashPropertyIndex implements PropertyIndex {
    private final Supplier<RedisCommands<String, String>> commands;
    private final RedisKeyspace keyspace;
    private final String property;

    public RedisHashPropertyIndex(Supplier<RedisCommands<String, String>> commands,
            RedisKeyspace keyspace, String property) {
        this.commands = Objects.requireNonNull(commands);
        this.keyspace = Objects.requireNonNull(keyspace);
        requireName(property);
        this.property = property;
    }

    public static String key(RedisKeyspace keyspace, String group, String property) {
        requireName(group);
        requireName(property);
        return keyspace.base() + ":matching:worker:index:" + encode(group) + ":" + encode(property);
    }

    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static void requireName(String value) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException("Group and property must be nonblank");
    }

    @Override public Map<String, String> lookup(String workerGroupId, List<String> values) {
        String key = key(keyspace, workerGroupId, property);
        Objects.requireNonNull(values, "values");
        if (values.size() > 100 || new HashSet<>(values).size() != values.size()
                || values.stream().anyMatch(value -> value == null || value.isEmpty()))
            throw new IllegalArgumentException("property lookup requires at most 100 unique nonempty values");
        if (values.isEmpty()) return Map.of();
        var result = new LinkedHashMap<String, String>();
        for (var row : commands.get().hmget(key, values.toArray(String[]::new))) {
            if (!row.hasValue()) continue;
            if (row.getValue().isBlank()) throw new IllegalStateException("corrupt Matching property index identity");
            result.put(row.getKey(), row.getValue());
        }
        return Collections.unmodifiableMap(result);
    }
}
