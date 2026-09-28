package com.xa.mass.workermatching.index;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.sync.RedisCommands;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.Supplier;

/** Persistent observation watermarks, independent of Facts, inventory and Score commits. */
public final class NetworkEvidenceTimestamps {
    private static final int STORAGE_BATCH_SIZE = 100;
    private static final String FILTER_AND_ADVANCE = """
            local function valid_time(value)
              return string.match(value, '^[1-9]%d*$')
                and (#value < 19 or (#value == 19 and value <= '9223372036854775807'))
            end
            local function less(a, b)
              return #a < #b or (#a == #b and a < b)
            end
            local accepted, updates = {}, {}
            for i = 1, #ARGV, 2 do
              local id, observed = ARGV[i], ARGV[i + 1]
              local previous = redis.call('HGET', KEYS[1], id)
              if previous and not valid_time(previous) then
                return redis.error_reply('Corrupt network evidence timestamp')
              end
              if not previous or not less(observed, previous) then
                accepted[#accepted + 1] = id
                if not previous or previous ~= observed then
                  updates[#updates + 1] = id
                  updates[#updates + 1] = observed
                end
              end
            end
            if #updates > 0 then redis.call('HSET', KEYS[1], unpack(updates)) end
            return accepted
            """;

    private final Supplier<RedisCommands<String, String>> commands;
    private final RedisKeyspace keyspace;

    public NetworkEvidenceTimestamps(Supplier<RedisCommands<String, String>> commands,
            RedisKeyspace keyspace) {
        this.commands = Objects.requireNonNull(commands, "commands");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
    }

    public static String key(RedisKeyspace keyspace, String workerGroupId) {
        requireName(workerGroupId, "workerGroupId");
        return keyspace.base() + ":matching:worker:platform-index:"
                + RedisHashPropertyIndex.encode(workerGroupId) + ":evidenceTimestamp";
    }

    /**
     * Filters caller-bounded, source-validated observations and advances accepted times.
     * Equal times pass. Each storage chunk prepares before writing; earlier chunks survive
     * a later failure. Acceptance makes no claim about a subsequent Score operation.
     */
    public Set<String> filterAndAdvance(String workerGroupId, Map<String, Long> observedAtByWorkerId) {
        String key = key(keyspace, workerGroupId);
        Objects.requireNonNull(observedAtByWorkerId, "observedAtByWorkerId");
        var captured = new LinkedHashMap<String, Long>();
        observedAtByWorkerId.forEach((id, time) -> {
            requireName(id, "workerId");
            if (time == null || time <= 0) throw new IllegalArgumentException("Evidence time must be positive");
            captured.put(id, time);
        });
        if (captured.isEmpty()) return Set.of();
        var entries = new ArrayList<>(captured.entrySet());
        var accepted = new LinkedHashSet<String>();
        var redis = commands.get();
        for (int offset = 0; offset < entries.size(); offset += STORAGE_BATCH_SIZE) {
            var args = new ArrayList<String>();
            for (var entry : entries.subList(offset, Math.min(offset + STORAGE_BATCH_SIZE, entries.size()))) {
                args.add(entry.getKey());
                args.add(Long.toString(entry.getValue()));
            }
            List<String> result = redis.eval(FILTER_AND_ADVANCE, ScriptOutputType.MULTI,
                    new String[]{key}, args.toArray(String[]::new));
            accepted.addAll(result);
        }
        return Collections.unmodifiableSet(accepted);
    }

    private static void requireName(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " must be nonblank");
    }
}
