package com.xa.mass.workermatching.storage;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.workermatching.PlatformLeaseState;
import com.xa.mass.workermatching.RuleInputs;
import io.lettuce.core.ScoredValue;
import io.lettuce.core.ZAddArgs;
import io.lettuce.core.api.sync.RedisCommands;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Supplier;

/** One resource-local ZSET; reads are caller-bounded and expired members need no deletion. */
public final class RedisPlatformLeaseState implements PlatformLeaseState {
    private static final int BATCH_LIMIT = 1000;
    private final Supplier<RedisCommands<String, String>> commands;
    private final RedisKeyspace keyspace;
    private final Map<String, Set<String>> resourcesByGroup;
    public RedisPlatformLeaseState(Supplier<RedisCommands<String, String>> commands, RedisKeyspace keyspace,
            Map<String, Set<String>> resourcesByGroup) {
        this.commands = Objects.requireNonNull(commands); this.keyspace = Objects.requireNonNull(keyspace);
        var captured = new HashMap<String, Set<String>>();
        resourcesByGroup.forEach((group, resources) -> captured.put(group, Set.copyOf(resources)));
        this.resourcesByGroup = Map.copyOf(captured);
    }
    public static String member(Coordinate coordinate) {
        return encode(coordinate.subject()) + "." + encode(coordinate.partition()) + "." + encode(coordinate.workerId());
    }
    public static String key(RedisKeyspace keyspace, String group, String pool) {
        return keyspace.base() + ":matching:lease:" + encode(RuleInputs.text(group)) + ":" + encode(RuleInputs.text(pool));
    }
    private static String encode(String value) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(value.getBytes(StandardCharsets.UTF_8));
    }
    private String admittedKey(String group, String pool, int size) {
        if (!resourcesByGroup.getOrDefault(group, Set.of()).contains(pool))
            throw new IllegalArgumentException("lease resource is not enabled in Group");
        if (size > BATCH_LIMIT) throw new IllegalArgumentException("lease batch exceeds " + BATCH_LIMIT);
        return key(keyspace, group, pool);
    }
    @Override public Map<Coordinate, Long> read(String group, String pool, List<Coordinate> coordinates) {
        var rows = List.copyOf(coordinates);
        String key = admittedKey(group, pool, rows.size());
        if (rows.isEmpty()) return Map.of();
        var scores = commands.get().zmscore(key, rows.stream().map(RedisPlatformLeaseState::member).toArray(String[]::new));
        var result = new LinkedHashMap<Coordinate, Long>();
        for (int i = 0; i < rows.size(); i++) {
            Double value = scores.get(i);
            if (value == null) continue;
            if (!Double.isFinite(value) || value < 1 || value > 9_999_999_999_900L || value != Math.rint(value))
                throw new IllegalStateException("invalid Matching lease deadline");
            result.put(rows.get(i), value.longValue());
        }
        return Collections.unmodifiableMap(result);
    }
    @Override public void record(String group, String pool, Map<Coordinate, Long> deadlines) {
        var rows = Map.copyOf(deadlines);
        String key = admittedKey(group, pool, rows.size());
        if (rows.isEmpty()) return;
        var values = new ArrayList<ScoredValue<String>>();
        rows.forEach((coordinate, until) -> {
            if (until < 1 || until > 9_999_999_999_900L) throw new IllegalArgumentException("invalid lease deadline");
            values.add(ScoredValue.just(until, member(coordinate)));
        });
        @SuppressWarnings("unchecked") ScoredValue<String>[] batch = values.toArray(ScoredValue[]::new);
        commands.get().zadd(key, ZAddArgs.Builder.gt(), batch);
    }
}
