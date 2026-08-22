package com.xa.mass.kernel.delivery.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RedisWorkerCommandRuntime
        implements WorkerCommandRuntime, AutoCloseable {

    private static final String OFFER = """
            local results = {}
            for index = 1, #ARGV, 2 do
                local worker_id = ARGV[index]
                local encoded_command = ARGV[index + 1]
                table.insert(
                    results,
                    redis.call(
                        'HSETNX',
                        KEYS[1],
                        worker_id,
                        encoded_command
                    )
                )
            end
            return results
            """;

    private static final String CONSUME_ONE = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if not current then
                return {}
            end
            redis.call('HDEL', KEYS[1], ARGV[1])
            return {ARGV[1], current}
            """;

    private static final String CONSUME_OBSERVED = """
            local results = {}
            for index = 1, #ARGV, 2 do
                local worker_id = ARGV[index]
                local observed = ARGV[index + 1]
                local current = redis.call('HGET', KEYS[1], worker_id)
                if current and current == observed then
                    redis.call('HDEL', KEYS[1], worker_id)
                    table.insert(results, worker_id)
                    table.insert(results, current)
                end
            end
            return results
            """;

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerCommandRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            RedisKeyspace keyspace
    ) {
        if (redisClient == null || codec == null) {
            throw new IllegalArgumentException(
                    "redisClient and codec must be present"
            );
        }
        this.redisClient = redisClient;
        this.codec = codec;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public Map<String, WorkerCommandAppendStatus> appendWorkerCommands(
            String endpointManagerId,
            Map<String, DeliveryCommand> workerCommandsByWorkerId
    ) {
        throw new KernelOperationNotImplementedException(
                "WorkerCommandRuntime",
                "append_worker_commands"
        );
    }

    @Override
    public Map<String, WorkerCommandOfferStatus> offerWorkerCommands(
            String endpointManagerId,
            Map<String, DeliveryCommand> workerCommandsByWorkerId
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        if (workerCommandsByWorkerId == null) {
            throw new IllegalArgumentException(
                    "workerCommandsByWorkerId must be present"
            );
        }
        if (workerCommandsByWorkerId.isEmpty()) {
            return Map.of();
        }

        long nowMillis = redisTimeMillis();
        List<String> workerIds = new ArrayList<>(
                workerCommandsByWorkerId.size()
        );
        List<String> arguments = new ArrayList<>(
                workerCommandsByWorkerId.size() * 2
        );
        workerCommandsByWorkerId.forEach((workerId, command) -> {
            requireNonBlank(workerId, "workerId");
            if (command == null
                    || command.executeBeforeMillis() <= nowMillis) {
                throw new IllegalArgumentException(
                        "Worker commands must have future deadlines"
                );
            }
            workerIds.add(workerId);
            arguments.add(workerId);
            arguments.add(codec.encodeDeliveryCommand(command));
        });

        List<?> rawResults = commands().eval(
                OFFER,
                ScriptOutputType.MULTI,
                new String[]{commandKey(endpointManagerId)},
                arguments.toArray(String[]::new)
        );
        if (rawResults == null
                || rawResults.size() != workerIds.size()) {
            throw new IllegalStateException(
                    "Redis Worker command offer returned an invalid response"
            );
        }
        Map<String, WorkerCommandOfferStatus> results =
                new LinkedHashMap<>();
        for (int index = 0; index < workerIds.size(); index++) {
            Object raw = rawResults.get(index);
            long inserted;
            try {
                inserted = raw instanceof Number number
                        ? number.longValue()
                        : Long.parseLong(String.valueOf(raw));
            } catch (NumberFormatException error) {
                throw new IllegalStateException(
                        "Redis Worker command offer returned an invalid response",
                        error
                );
            }
            if (inserted != 0L && inserted != 1L) {
                throw new IllegalStateException(
                        "Redis Worker command offer returned an invalid response"
                );
            }
            results.put(
                    workerIds.get(index),
                    inserted == 1L
                            ? WorkerCommandOfferStatus.OFFERED
                            : WorkerCommandOfferStatus.OCCUPIED
            );
        }
        return Collections.unmodifiableMap(results);
    }

    @Override
    public DeliveryCommand consumeWorkerCommand(
            String endpointManagerId,
            String workerId
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        requireNonBlank(workerId, "workerId");
        List<?> values = commands().eval(
                CONSUME_ONE,
                ScriptOutputType.MULTI,
                new String[]{commandKey(endpointManagerId)},
                workerId
        );
        if (values == null || values.isEmpty()) {
            return null;
        }
        if (values.size() != 2) {
            throw new IllegalStateException(
                    "Redis DeliveryCommand script returned an invalid response"
            );
        }
        return activeCommand(
                String.valueOf(values.get(1)),
                redisTimeMillis()
        );
    }

    @Override
    public Map<String, DeliveryCommand> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "consume limit must be positive"
            );
        }

        String key = commandKey(endpointManagerId);
        List<KeyValue<String, String>> observed =
                commands().hrandfieldWithvalues(
                        key,
                        limit
                );
        if (observed.isEmpty()) {
            return Map.of();
        }

        List<String> arguments = new ArrayList<>(
                observed.size() * 2
        );
        observed.forEach(entry -> {
            arguments.add(entry.getKey());
            arguments.add(entry.getValue());
        });
        List<?> consumed = commands().eval(
                CONSUME_OBSERVED,
                ScriptOutputType.MULTI,
                new String[]{key},
                arguments.toArray(String[]::new)
        );
        if (consumed == null || consumed.isEmpty()) {
            return Map.of();
        }
        if (consumed.size() % 2 != 0) {
            throw new IllegalStateException(
                    "Redis DeliveryCommand script returned an invalid response"
            );
        }

        Map<String, DeliveryCommand> active = new LinkedHashMap<>();
        long nowMillis = redisTimeMillis();
        for (int index = 0; index < consumed.size(); index += 2) {
            String workerId = String.valueOf(consumed.get(index));
            DeliveryCommand command = activeCommand(
                    String.valueOf(consumed.get(index + 1)),
                    nowMillis
            );
            if (command != null) {
                active.put(workerId, command);
            }
        }
        return Map.copyOf(active);
    }

    private DeliveryCommand activeCommand(
            String encoded,
            long nowMillis
    ) {
        DeliveryCommand command = codec.decodeDeliveryCommand(encoded);
        if (command == null
                || command.executeBeforeMillis() <= nowMillis) {
            return null;
        }
        return command;
    }

    private long redisTimeMillis() {
        List<String> parts = commands().time();
        if (parts.size() != 2) {
            throw new IllegalStateException(
                    "Redis TIME returned an invalid response"
            );
        }
        return Long.parseLong(parts.get(0)) * 1_000L
                + Long.parseLong(parts.get(1)) / 1_000L;
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

    private String commandKey(String endpointManagerId) {
        return keyspace.base() + ":delivery:commands:" + endpointManagerId;
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }
}
