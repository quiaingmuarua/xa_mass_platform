package com.xa.mass.kernel.assignment.redis;

import com.xa.mass.kernel.assignment.CandidateWorkerCache;
import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

public final class RedisCandidateWorkerCache
        implements CandidateWorkerCache, AutoCloseable {

    private static final String APPEND_CANDIDATES = """
            local now_millis = tonumber(ARGV[1])
            local expires_at_millis = tonumber(ARGV[2])
            local maximum = tonumber(ARGV[3])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_millis)
            local available = maximum - redis.call('ZCARD', KEYS[1])
            local accepted = {}
            if available <= 0 then
                return accepted
            end
            for index = 4, #ARGV, 2 do
                if available <= 0 then
                    break
                end
                local worker_id = ARGV[index]
                local encoded = ARGV[index + 1]
                if redis.call(
                    'ZADD', KEYS[1], 'NX', expires_at_millis, encoded
                ) == 1 then
                    table.insert(accepted, worker_id)
                    available = available - 1
                end
            end
            return accepted
            """;

    private static final String CONSUME_CANDIDATES = """
            local now_millis = tonumber(ARGV[1])
            local limit = tonumber(ARGV[2])

            redis.call('ZREMRANGEBYSCORE', KEYS[1], '-inf', now_millis)
            local entries = redis.call(
                'ZRANGEBYSCORE', KEYS[1], '(' .. now_millis, '+inf',
                'LIMIT', 0, limit
            )
            if #entries > 0 then
                redis.call('ZREM', KEYS[1], unpack(entries))
            end
            return entries
            """;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisCandidateWorkerCache(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this.redisClient = java.util.Objects.requireNonNull(
                redisClient,
                "redisClient"
        );
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public List<String> appendCandidateWorkers(
            String candidateId,
            int maximumCandidateWorkers,
            List<CandidateWorkerEntry> candidateWorkers,
            long expiresAtMillis
    ) {
        requireCandidateId(candidateId);
        if (maximumCandidateWorkers <= 0) {
            throw new IllegalArgumentException(
                    "maximumCandidateWorkers must be positive"
            );
        }
        if (candidateWorkers == null) {
            throw new IllegalArgumentException(
                    "candidateWorkers must be present"
            );
        }
        if (expiresAtMillis <= 0) {
            throw new IllegalArgumentException(
                    "candidate batch expiry must be positive"
            );
        }
        if (candidateWorkers.isEmpty()) {
            return List.of();
        }
        long nowMillis = redisTimeMillis();
        if (expiresAtMillis <= nowMillis) {
            throw new IllegalArgumentException(
                    "candidate batch expiry must be in the future"
            );
        }
        LinkedHashSet<String> workerIds = new LinkedHashSet<>();
        List<String> arguments = new ArrayList<>();
        arguments.add(Long.toString(nowMillis));
        arguments.add(Long.toString(expiresAtMillis));
        arguments.add(Integer.toString(maximumCandidateWorkers));
        for (CandidateWorkerEntry entry : candidateWorkers) {
            validateEntry(entry);
            if (!workerIds.add(entry.workerId())) {
                throw new IllegalArgumentException(
                        "candidateWorkers must be unique by workerId"
                );
            }
            arguments.add(entry.workerId());
            arguments.add(encode(entry));
        }
        Object raw = commands().eval(
                APPEND_CANDIDATES,
                ScriptOutputType.MULTI,
                new String[]{candidateKey(candidateId)},
                arguments.toArray(String[]::new)
        );
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        return values.stream().map(String::valueOf).toList();
    }

    @Override
    public Map<String, Integer> candidateWorkerCounts(
            List<String> candidateIds
    ) {
        if (candidateIds == null) {
            throw new IllegalArgumentException(
                    "candidateIds must be present"
            );
        }
        LinkedHashSet<String> uniqueIds = new LinkedHashSet<>();
        candidateIds.forEach(candidateId -> {
            requireCandidateId(candidateId);
            uniqueIds.add(candidateId);
        });
        if (uniqueIds.isEmpty()) {
            return Map.of();
        }
        long nowMillis = redisTimeMillis();
        LinkedHashMap<String, Integer> counts = new LinkedHashMap<>();
        for (String candidateId : uniqueIds) {
            String key = candidateKey(candidateId);
            commands().zremrangebyscore(
                    key,
                    Double.NEGATIVE_INFINITY,
                    nowMillis
            );
            counts.put(
                    candidateId,
                    Math.toIntExact(commands().zcount(
                            key,
                            io.lettuce.core.Range.from(
                                    io.lettuce.core.Range.Boundary
                                            .excluding((double) nowMillis),
                                    io.lettuce.core.Range.Boundary
                                            .unbounded()
                            )
                    ))
            );
        }
        return counts;
    }

    @Override
    public List<CandidateWorkerEntry> consumeCandidateWorkers(
            String candidateId,
            int limit
    ) {
        requireCandidateId(candidateId);
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "consume limit must be positive"
            );
        }
        Object raw = commands().eval(
                CONSUME_CANDIDATES,
                ScriptOutputType.MULTI,
                new String[]{candidateKey(candidateId)},
                Long.toString(redisTimeMillis()),
                Integer.toString(limit)
        );
        if (!(raw instanceof List<?> values) || values.isEmpty()) {
            return List.of();
        }
        List<CandidateWorkerEntry> entries = new ArrayList<>(values.size());
        for (Object value : values) {
            CandidateWorkerEntry entry = decode(String.valueOf(value));
            if (entry != null) {
                entries.add(entry);
            }
        }
        return List.copyOf(entries);
    }

    private String encode(CandidateWorkerEntry entry) {
        try {
            Map<String, Object> value = new TreeMap<>();
            value.put("workerId", entry.workerId());
            value.put(
                    "heldWorkerLeaseScore",
                    entry.heldWorkerLeaseScore()
            );
            return mapper.writeValueAsString(value);
        } catch (JacksonException error) {
            throw new IllegalStateException(
                    "Candidate Worker entry could not be encoded",
                    error
            );
        }
    }

    private CandidateWorkerEntry decode(String encoded) {
        try {
            JsonNode value = mapper.readTree(encoded);
            if (value == null || !value.isObject() || value.size() != 2) {
                return null;
            }
            JsonNode workerId = value.get("workerId");
            JsonNode workerLeaseScore = value.get("heldWorkerLeaseScore");
            if (workerId == null || !workerId.isTextual()
                    || workerId.textValue().isEmpty()
                    || workerLeaseScore == null
                    || !workerLeaseScore.isIntegralNumber()
                    || workerLeaseScore.longValue() <= 0) {
                return null;
            }
            return new CandidateWorkerEntry(
                    workerId.textValue(),
                    workerLeaseScore.longValue()
            );
        } catch (JacksonException | IllegalArgumentException error) {
            return null;
        }
    }

    private static void validateEntry(CandidateWorkerEntry entry) {
        if (entry == null
                || entry.workerId().isBlank()
                || entry.heldWorkerLeaseScore() <= 0) {
            throw new IllegalArgumentException(
                    "Candidate Worker entry is invalid"
            );
        }
    }

    private long redisTimeMillis() {
        List<String> values = commands().time();
        return Long.parseLong(values.get(0)) * 1_000L
                + Long.parseLong(values.get(1)) / 1_000L;
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

    private String candidateKey(String candidateId) {
        return keyspace.base()
                + ":dispatch:candidate:"
                + candidateId
                + ":workers";
    }

    private static void requireCandidateId(String candidateId) {
        if (candidateId == null || candidateId.isBlank()) {
            throw new IllegalArgumentException(
                    "candidateId must be non-blank"
            );
        }
    }

    @Override
    public void close() {
        StatefulRedisConnection<String, String> current = connection;
        if (current != null) {
            current.close();
        }
    }
}
