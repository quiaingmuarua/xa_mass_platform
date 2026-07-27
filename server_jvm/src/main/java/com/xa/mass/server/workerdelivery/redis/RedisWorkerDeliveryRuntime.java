package com.xa.mass.server.workerdelivery.redis;

import static com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.classifyOutcomeCode;
import static com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.requireNonBlank;

import com.xa.mass.server.workerdelivery.WorkerDeliveryRuntime;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandPage;
import io.lettuce.core.MapScanCursor;
import io.lettuce.core.ScanArgs;
import io.lettuce.core.ScanCursor;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.springframework.stereotype.Component;

@Component
public final class RedisWorkerDeliveryRuntime implements WorkerDeliveryRuntime {

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

    public RedisWorkerDeliveryRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            WorkerDeliveryRedisProperties properties
    ) {
        this.redisClient = redisClient;
        this.codec = codec;
        this.prefix = properties.redisPrefix();
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
        WorkerCommandEnvelope command = codec.decodeWorkerCommand(
                String.valueOf(values.get(1))
        );
        if (command == null
                || command.executeBeforeMillis() <= redisTimeMillis()) {
            return null;
        }
        return command;
    }

    @Override
    public WorkerCommandPage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        if (scanCount <= 0) {
            throw new IllegalArgumentException("scanCount must be positive");
        }
        if (cursor != null
                && !com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol
                .isDecimal(cursor)) {
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
            return new WorkerCommandPage(Map.of(), nextCursor);
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
            return new WorkerCommandPage(Map.of(), nextCursor);
        }
        if (consumed.size() % 2 != 0) {
            throw new IllegalStateException(
                    "Redis WorkerCommand script returned an invalid response"
            );
        }

        long nowMillis = redisTimeMillis();
        Map<String, WorkerCommandEnvelope> active = new LinkedHashMap<>();
        for (int index = 0; index < consumed.size(); index += 2) {
            String workerId = String.valueOf(consumed.get(index));
            WorkerCommandEnvelope command = codec.decodeWorkerCommand(
                    String.valueOf(consumed.get(index + 1))
            );
            if (command != null
                    && command.executeBeforeMillis() > nowMillis) {
                active.put(workerId, command);
            }
        }
        return new WorkerCommandPage(active, nextCursor);
    }

    @Override
    public int appendSeedResults(List<SeedResult> results) {
        if (results.isEmpty()) {
            return 0;
        }
        Map<SeedResultOutcomeClass, List<String>> grouped =
                new EnumMap<>(SeedResultOutcomeClass.class);
        for (SeedResult result : results) {
            SeedResultOutcomeClass outcomeClass =
                    classifyOutcomeCode(result.outcomeCode());
            if (outcomeClass == null) {
                throw new IllegalArgumentException(
                        "SeedResult outcome code is invalid"
                );
            }
            grouped.computeIfAbsent(
                    outcomeClass,
                    ignored -> new ArrayList<>()
            ).add(codec.encodeSeedResult(result));
        }
        grouped.forEach((outcomeClass, encodedResults) ->
                commands().rpush(
                        resultKey(outcomeClass),
                        encodedResults.toArray(String[]::new)
                )
        );
        return results.size();
    }

    public boolean ping() {
        return "PONG".equals(commands().ping());
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
        if (current != null && current.isOpen()) {
            return current;
        }
        synchronized (this) {
            current = connection;
            if (current == null || !current.isOpen()) {
                current = redisClient.connect(StringCodec.UTF8);
                connection = current;
            }
            return current;
        }
    }

    private String commandKey(String endpointManagerId) {
        return "wd:" + prefix + ":endpoint-manager:"
                + endpointManagerId + ":worker-commands";
    }

    private String resultKey(SeedResultOutcomeClass outcomeClass) {
        return "rr:" + prefix + ":seed-results:"
                + outcomeClass.redisKeySuffix();
    }
}
