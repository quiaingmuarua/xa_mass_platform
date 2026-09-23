package com.xa.mass.server.worker.identity;

import com.xa.mass.kernel.redis.RedisKeyspace;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

final class RedisWorkerIdentityRegistry
        implements WorkerIdentityRegistry, AutoCloseable {

    private static final String REGISTER_SCRIPT = """
            local result = {}
            for i = 1, #ARGV, 2 do
              local current = redis.call('HGET', KEYS[1], ARGV[i])
              if not current then
                current = ARGV[i + 1]
                redis.call('HSET', KEYS[1], ARGV[i], current)
              end
              result[#result + 1] = current
            end
            return result
            """;

    private final RedisClient redisClient;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    RedisWorkerIdentityRegistry(
            RedisClient redisClient,
            RedisKeyspace keyspace
    ) {
        this.redisClient = Objects.requireNonNull(redisClient, "redisClient");
        this.keyspace = Objects.requireNonNull(keyspace, "keyspace");
    }

    @Override
    public List<String> registerAll(String workerGroupId, List<String> registrationKeys) {
        List<String> arguments = new ArrayList<>(registrationKeys.size() * 2);
        for (String registrationKey : registrationKeys) {
            arguments.add(registrationKey);
            arguments.add(UUID.randomUUID().toString());
        }
        return commands().eval(REGISTER_SCRIPT, ScriptOutputType.MULTI,
                new String[]{workerIdsKey(workerGroupId)}, arguments.toArray(String[]::new));
    }

    private String workerIdsKey(String workerGroupId) {
        return keyspace.base() + ":worker:identity:" + workerGroupId;
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
}
