package com.xa.mass.kernel.delivery.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RedisWorkerCommandRuntime
        implements WorkerCommandRuntime, AutoCloseable {

    private static final String CONSUME_ONE = """
            local current = redis.call('HGET', KEYS[1], ARGV[1])
            if not current then
                return {}
            end
            redis.call('HDEL', KEYS[1], ARGV[1])
            return {ARGV[1], current}
            """;

    private static final String CONSUME_SCANNED = """
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
    private final String prefix;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerCommandRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            String prefix
    ) {
        if (redisClient == null || codec == null) {
            throw new IllegalArgumentException(
                    "redisClient and codec must be present"
            );
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        this.redisClient = redisClient;
        this.codec = codec;
        this.prefix = prefix;
    }

    @Override
    public Map<String, WorkerCommandAppendStatus> appendWorkerCommands(
            String endpointManagerId,
            Map<String, WorkerCommandEnvelope> workerCommandsByWorkerId
    ) {
        throw new KernelOperationNotImplementedException(
                "WorkerCommandRuntime",
                "append_worker_commands"
        );
    }

    @Override
    public WorkerCommandEnvelope consumeWorkerCommand(
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
                    "Redis WorkerCommand script returned an invalid response"
            );
        }
        return activeCommand(String.valueOf(values.get(1)));
    }

    @Override
    public WorkerCommandConsumePage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        if (scanCount <= 0) {
            throw new IllegalArgumentException("scanCount must be positive");
        }
        if (cursor != null && !isDecimal(cursor)) {
            throw new IllegalArgumentException(
                    "cursor must be a non-negative Redis cursor"
            );
        }

        String key = commandKey(endpointManagerId);
        MapScanCursor<String, String> scanned = commands().hscan(
                key,
                ScanCursor.of(cursor == null ? "0" : cursor),
                new ScanArgs().limit(scanCount)
        );
        String nextCursor = scanned.isFinished()
                ? null
                : scanned.getCursor();
        if (scanned.getMap().isEmpty()) {
            return new WorkerCommandConsumePage(Map.of(), nextCursor);
        }

        List<String> arguments = new ArrayList<>(
                scanned.getMap().size() * 2
        );
        scanned.getMap().forEach((workerId, value) -> {
            arguments.add(workerId);
            arguments.add(value);
        });
        List<?> consumed = commands().eval(
                CONSUME_SCANNED,
                ScriptOutputType.MULTI,
                new String[]{key},
                arguments.toArray(String[]::new)
        );
        if (consumed == null || consumed.isEmpty()) {
            return new WorkerCommandConsumePage(Map.of(), nextCursor);
        }
        if (consumed.size() % 2 != 0) {
            throw new IllegalStateException(
                    "Redis WorkerCommand script returned an invalid response"
            );
        }

        Map<String, WorkerCommandEnvelope> active = new LinkedHashMap<>();
        for (int index = 0; index < consumed.size(); index += 2) {
            String workerId = String.valueOf(consumed.get(index));
            WorkerCommandEnvelope command = activeCommand(
                    String.valueOf(consumed.get(index + 1))
            );
            if (command != null) {
                active.put(workerId, command);
            }
        }
        return new WorkerCommandConsumePage(active, nextCursor);
    }

    private WorkerCommandEnvelope activeCommand(String encoded) {
        WorkerCommandEnvelope command = codec.decodeWorkerCommand(encoded);
        if (command == null
                || command.executeBeforeMillis() <= redisTimeMillis()) {
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
        return "wd:" + prefix + ":endpoint-manager:"
                + endpointManagerId + ":worker-commands";
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
        }
    }

    private static boolean isDecimal(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
