package com.xa.mass.kernel.serviceability.redis;

import com.xa.mass.kernel.KernelOperationNotImplementedException;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** JVM bridge for Server access to the Kernel-owned Serviceability handoff. */
public final class RedisWorkerServiceabilityRuntime
        implements WorkerServiceabilityRuntime, AutoCloseable {

    public static final int MAX_BATCH_SIZE = 100;
    public static final int DEFAULT_RESULT_CAPACITY = 10_000;

    private static final String CONSUME_PROBE_REQUESTS = """
            local worker_ids = redis.call(
                'HRANDFIELD',
                KEYS[1],
                tonumber(ARGV[1])
            )
            if not worker_ids or #worker_ids == 0 then
                return {}
            end
            for _, worker_id in ipairs(worker_ids) do
                redis.call('HDEL', KEYS[1], worker_id)
            end
            return worker_ids
            """;

    private static final String APPEND_PROBE_RESULTS = """
            local remaining = tonumber(ARGV[1]) - redis.call('LLEN', KEYS[1])
            if remaining <= 0 then
                return 0
            end
            local accepted = math.min(remaining, #ARGV - 1)
            for index = 1, accepted do
                redis.call('RPUSH', KEYS[1], ARGV[index + 1])
            end
            return accepted
            """;

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final String prefix;
    private final int resultCapacity;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerServiceabilityRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            String prefix
    ) {
        this(
                redisClient,
                codec,
                prefix,
                DEFAULT_RESULT_CAPACITY
        );
    }

    public RedisWorkerServiceabilityRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            String prefix,
            int resultCapacity
    ) {
        if (redisClient == null || codec == null) {
            throw new IllegalArgumentException(
                    "redisClient and codec must be present"
            );
        }
        if (prefix == null || prefix.isBlank()) {
            throw new IllegalArgumentException("prefix must be non-blank");
        }
        if (resultCapacity <= 0) {
            throw new IllegalArgumentException(
                    "resultCapacity must be positive"
            );
        }
        this.redisClient = redisClient;
        this.codec = codec;
        this.prefix = prefix;
        this.resultCapacity = resultCapacity;
    }

    @Override
    public Map<String, ProbeRequestOfferStatus> offerProbeRequests(
            String adapterId,
            List<String> workerIds
    ) {
        throw new KernelOperationNotImplementedException(
                "WorkerServiceabilityRuntime",
                "offer_probe_requests"
        );
    }

    @Override
    public List<String> consumeProbeRequests(String adapterId, int limit) {
        requireNonBlank(adapterId, "adapterId");
        requireLimit(limit);
        List<?> raw = commands().eval(
                CONSUME_PROBE_REQUESTS,
                ScriptOutputType.MULTI,
                new String[]{requestKey(adapterId)},
                Integer.toString(limit)
        );
        if (raw == null || raw.isEmpty()) {
            return List.of();
        }
        if (raw.size() > limit) {
            throw new IllegalStateException(
                    "Redis probe consume returned an invalid response"
            );
        }
        List<String> workerIds = new ArrayList<>(raw.size());
        for (Object value : raw) {
            String workerId = String.valueOf(value);
            requireNonBlank(workerId, "workerId");
            if (workerIds.contains(workerId)) {
                throw new IllegalStateException(
                        "Redis probe consume returned duplicate Worker ids"
                );
            }
            workerIds.add(workerId);
        }
        return List.copyOf(workerIds);
    }

    @Override
    public int appendProbeResults(List<DeliveryReport> reports) {
        if (reports == null) {
            throw new IllegalArgumentException("reports must be present");
        }
        if (reports.isEmpty()) {
            return 0;
        }
        if (reports.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Probe result append exceeds 100 Reports"
            );
        }
        List<String> arguments = new ArrayList<>(reports.size() + 1);
        arguments.add(Integer.toString(resultCapacity));
        for (DeliveryReport report : reports) {
            if (report == null
                    || report.src() != DeliveryEndpoint.ADAPTER
                    || report.dst() != DeliveryEndpoint.KERNEL) {
                throw new IllegalArgumentException(
                        "Probe Report source or destination is invalid"
                );
            }
            arguments.add(codec.encodeDeliveryReport(report));
        }
        Long accepted = commands().eval(
                APPEND_PROBE_RESULTS,
                ScriptOutputType.INTEGER,
                new String[]{resultKey()},
                arguments.toArray(String[]::new)
        );
        if (accepted == null
                || accepted < 0L
                || accepted > reports.size()) {
            throw new IllegalStateException(
                    "Redis probe append returned an invalid response"
            );
        }
        return accepted.intValue();
    }

    @Override
    public List<DeliveryReport> consumeProbeResults(int limit) {
        throw new KernelOperationNotImplementedException(
                "WorkerServiceabilityRuntime",
                "consume_probe_results"
        );
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

    private String requestKey(String adapterId) {
        return "ws:{" + prefix + "}:adapter:"
                + adapterId + ":probe-requests";
    }

    private String resultKey() {
        return "ws:{" + prefix + "}:probe-results";
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 100"
            );
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must be non-blank");
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
