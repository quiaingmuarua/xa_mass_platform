package com.xa.mass.kernel.serviceability.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
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
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/** JVM bridge for Server access to the Kernel-owned Serviceability handoff. */
public final class RedisWorkerServiceabilityRuntime
        implements WorkerServiceabilityRuntime, AutoCloseable {

    public static final int MAX_BATCH_SIZE = 100;
    public static final int DEFAULT_REQUEST_CAPACITY_PER_ADAPTER = 10_000;
    public static final int DEFAULT_RESULT_CAPACITY = 10_000;

    private static final String OFFER_PROBE_REQUESTS = """
            local capacity = tonumber(ARGV[1])
            local size = redis.call('HLEN', KEYS[1])
            local results = {}
            for index = 2, #ARGV do
                local worker_id = ARGV[index]
                if redis.call('HEXISTS', KEYS[1], worker_id) == 1 then
                    table.insert(results, 'ALREADY_REQUESTED')
                elseif size >= capacity then
                    table.insert(results, 'CAPACITY')
                else
                    redis.call('HSET', KEYS[1], worker_id, '1')
                    size = size + 1
                    table.insert(results, 'OFFERED')
                end
            end
            return results
            """;

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

    private static final String APPEND_ADAPTER_EVIDENCE_RESULTS = """
            local batch_size = #ARGV - 1
            local current_size = redis.call('LLEN', KEYS[1])
            if current_size + batch_size > tonumber(ARGV[1]) then
                return 0
            end
            for index = 1, batch_size do
                redis.call('RPUSH', KEYS[1], ARGV[index + 1])
            end
            return batch_size
            """;

    private final RedisClient redisClient;
    private final WorkerDeliveryCodec codec;
    private final RedisKeyspace keyspace;
    private final int requestCapacityPerAdapter;
    private final int resultCapacity;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerServiceabilityRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            RedisKeyspace keyspace
    ) {
        this(
                redisClient,
                codec,
                keyspace,
                DEFAULT_REQUEST_CAPACITY_PER_ADAPTER,
                DEFAULT_RESULT_CAPACITY
        );
    }

    public RedisWorkerServiceabilityRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            RedisKeyspace keyspace,
            int resultCapacity
    ) {
        this(
                redisClient,
                codec,
                keyspace,
                DEFAULT_REQUEST_CAPACITY_PER_ADAPTER,
                resultCapacity
        );
    }

    public RedisWorkerServiceabilityRuntime(
            RedisClient redisClient,
            WorkerDeliveryCodec codec,
            RedisKeyspace keyspace,
            int requestCapacityPerAdapter,
            int resultCapacity
    ) {
        if (redisClient == null || codec == null) {
            throw new IllegalArgumentException(
                    "redisClient and codec must be present"
            );
        }
        if (requestCapacityPerAdapter <= 0 || resultCapacity <= 0) {
            throw new IllegalArgumentException(
                    "serviceability capacities must be positive"
            );
        }
        this.redisClient = redisClient;
        this.codec = codec;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
        this.requestCapacityPerAdapter = requestCapacityPerAdapter;
        this.resultCapacity = resultCapacity;
    }

    @Override
    public Map<String, ProbeRequestOfferStatus> offerProbeRequests(
            String adapterId,
            List<String> workerIds
    ) {
        requireNonBlank(adapterId, "adapterId");
        List<String> bounded = boundedWorkerIds(workerIds, true);
        if (bounded.isEmpty()) {
            return Map.of();
        }
        List<String> arguments = new ArrayList<>(bounded.size() + 1);
        arguments.add(Integer.toString(requestCapacityPerAdapter));
        arguments.addAll(bounded);
        List<?> raw = commands().eval(
                OFFER_PROBE_REQUESTS,
                ScriptOutputType.MULTI,
                new String[]{requestKey(adapterId)},
                arguments.toArray(String[]::new)
        );
        if (raw == null || raw.size() != bounded.size()) {
            throw new IllegalStateException(
                    "Redis probe offer returned an invalid response"
            );
        }
        Map<String, ProbeRequestOfferStatus> results = new LinkedHashMap<>();
        for (int index = 0; index < bounded.size(); index++) {
            ProbeRequestOfferStatus status;
            try {
                status = ProbeRequestOfferStatus.valueOf(
                        String.valueOf(raw.get(index))
                );
            } catch (IllegalArgumentException error) {
                throw new IllegalStateException(
                        "Redis probe offer returned an invalid response",
                        error
                );
            }
            results.put(bounded.get(index), status);
        }
        return results;
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
    public int appendAdapterEvidenceResults(List<DeliveryReport> reports) {
        if (reports == null) {
            throw new IllegalArgumentException("reports must be present");
        }
        if (reports.isEmpty()) {
            return 0;
        }
        if (reports.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "Adapter evidence append exceeds 100 Reports"
            );
        }
        List<String> arguments = new ArrayList<>(reports.size() + 1);
        arguments.add(Integer.toString(resultCapacity));
        for (DeliveryReport report : reports) {
            if (report == null
                    || report.src() != DeliveryEndpoint.ADAPTER
                    || report.dst() != DeliveryEndpoint.KERNEL) {
                throw new IllegalArgumentException(
                        "Adapter evidence source or destination is invalid"
                );
            }
            arguments.add(codec.encodeDeliveryReport(report));
        }
        Long accepted = commands().eval(
                APPEND_ADAPTER_EVIDENCE_RESULTS,
                ScriptOutputType.INTEGER,
                new String[]{resultKey()},
                arguments.toArray(String[]::new)
        );
        if (accepted == null
                || accepted < 0L
                || accepted > reports.size()) {
            throw new IllegalStateException(
                    "Redis Adapter evidence append returned an invalid response"
            );
        }
        return accepted.intValue();
    }

    @Override
    public List<DeliveryReport> consumeAdapterEvidenceResults(int limit) {
        requireLimit(limit);
        List<String> encoded = commands().lpop(resultKey(), limit);
        if (encoded == null || encoded.isEmpty()) {
            return List.of();
        }
        List<DeliveryReport> reports = new ArrayList<>(encoded.size());
        for (String value : encoded) {
            DeliveryReport report = codec.decodeDeliveryReport(value);
            if (report != null
                    && report.src() == DeliveryEndpoint.ADAPTER
                    && report.dst() == DeliveryEndpoint.KERNEL) {
                reports.add(report);
            }
        }
        return List.copyOf(reports);
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
        return keyspace.base() + ":worker:serviceability:adapter:"
                + adapterId + ":probe_requests";
    }

    private String resultKey() {
        return keyspace.base()
                + ":worker:serviceability:evidence_results";
    }

    private static void requireLimit(int limit) {
        if (limit < 1 || limit > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "limit must be between 1 and 100"
            );
        }
    }

    private static List<String> boundedWorkerIds(
            List<String> workerIds,
            boolean allowEmpty
    ) {
        if (workerIds == null) {
            throw new IllegalArgumentException("workerIds must be present");
        }
        if ((!allowEmpty && workerIds.isEmpty())
                || workerIds.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException(
                    "workerIds must contain at most 100 ids"
            );
        }
        LinkedHashSet<String> unique = new LinkedHashSet<>();
        for (String workerId : workerIds) {
            requireNonBlank(workerId, "workerId");
            if (!unique.add(workerId)) {
                throw new IllegalArgumentException(
                        "workerIds must be unique"
                );
            }
        }
        return List.copyOf(unique);
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
