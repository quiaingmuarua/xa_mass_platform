package com.xa.mass.kernel.worker.redis;

import com.xa.mass.kernel.redis.RedisKeyspace;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationResult;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.RegistrationStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerDescriptor;
import com.xa.mass.kernel.worker.WorkerResourceCatalog.WorkerGroupDescriptor;
import io.lettuce.core.KeyValue;
import io.lettuce.core.RedisClient;
import io.lettuce.core.ScriptOutputType;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import io.lettuce.core.codec.StringCodec;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;

public final class RedisWorkerResourceCatalog
        implements WorkerResourceCatalog, AutoCloseable {

    private static final String REGISTER_BINDINGS_SCRIPT = """
            local result = {}
            local group_exists = redis.call('HEXISTS', KEYS[1], ARGV[1]) == 1
            for i = 4, #ARGV do
              local status, endpoint = 'NOT_FOUND', ''
              if group_exists then
                local stored = redis.call('HGET', KEYS[2], ARGV[i])
                if not stored then
                  redis.call('HSET', KEYS[2], ARGV[i], ARGV[3])
                  status, endpoint = 'OK', ARGV[2]
                else
                  local valid, binding = pcall(cjson.decode, stored)
                  local fields = 0
                  if valid and type(binding) == 'table' then
                    for _ in pairs(binding) do fields = fields + 1 end
                  end
                  if not valid or type(binding) ~= 'table' or fields ~= 2
                    or type(binding.workerGroupId) ~= 'string' or binding.workerGroupId == ''
                    or type(binding.endpointManagerId) ~= 'string' or binding.endpointManagerId == '' then
                    status = 'INVALID'
                  elseif binding.workerGroupId ~= ARGV[1] then
                    status = 'CONFLICT'
                  else
                    status, endpoint = 'NOOP', binding.endpointManagerId
                  end
                end
              end
              result[#result + 1] = status
              result[#result + 1] = endpoint
            end
            return result
            """;

    private final RedisClient redisClient;
    private final WorkerScoreCore scoreCore;
    private final RedisKeyspace keyspace;
    private volatile StatefulRedisConnection<String, String> connection;

    public RedisWorkerResourceCatalog(
            RedisClient redisClient,
            WorkerScoreCore scoreCore,
            RedisKeyspace keyspace
    ) {
        if (redisClient == null) {
            throw new IllegalArgumentException("redisClient must be present");
        }
        if (scoreCore == null) {
            throw new IllegalArgumentException("scoreCore must be present");
        }
        this.redisClient = redisClient;
        this.scoreCore = scoreCore;
        this.keyspace = java.util.Objects.requireNonNull(
                keyspace,
                "keyspace"
        );
    }

    @Override
    public RegistrationResult registerWorkerGroup(
            WorkerGroupDescriptor descriptor
    ) {
        if (descriptor == null) {
            return result(
                    RegistrationStatus.INVALID,
                    "invalid workerGroup descriptor"
            );
        }
        String encoded = WorkerRedisSupport.encodeWorkerGroup(descriptor);
        if (encoded == null) {
            return result(RegistrationStatus.INVALID, "invalid descriptor json");
        }
        if (commands().hsetnx(
                groupsKey(),
                descriptor.workerGroupId(),
                encoded
        )) {
            return new RegistrationResult(RegistrationStatus.OK);
        }
        WorkerGroupDescriptor current = WorkerRedisSupport.decodeWorkerGroup(
                commands().hget(groupsKey(), descriptor.workerGroupId())
        );
        if (current == null
                || !current.workerGroupId().equals(descriptor.workerGroupId())) {
            return result(
                    RegistrationStatus.INVALID,
                    "stored worker group descriptor is invalid"
            );
        }
        return current.equals(descriptor)
                ? new RegistrationResult(RegistrationStatus.NOOP)
                : result(
                        RegistrationStatus.CONFLICT,
                        "worker group is already registered with a different descriptor"
                );
    }

    @Override
    public Map<String, WorkerRegistrationResult> registerWorkers(
            String workerGroupId,
            List<String> workerIds,
            String defaultEndpointManagerId
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        requireNonBlank(defaultEndpointManagerId, "defaultEndpointManagerId");
        requireWorkerIds(workerIds);
        if (workerIds.isEmpty() || new HashSet<>(workerIds).size() != workerIds.size()) {
            throw new IllegalArgumentException("workerIds must contain 1..100 unique IDs");
        }
        List<String> arguments = new ArrayList<>();
        arguments.add(workerGroupId);
        arguments.add(defaultEndpointManagerId);
        arguments.add(WorkerRedisSupport.encodeBinding(workerGroupId, defaultEndpointManagerId));
        arguments.addAll(workerIds);
        List<String> rows = commands().eval(
                REGISTER_BINDINGS_SCRIPT, ScriptOutputType.MULTI,
                new String[]{groupsKey(), WorkerRedisSupport.bindingsKey(keyspace)},
                arguments.toArray(String[]::new)
        );
        List<String> accepted = new ArrayList<>();
        // Binding Lua returns an actual Endpoint only for accepted registrations.
        for (int i = 0; i < workerIds.size(); i++) {
            if (!rows.get(i * 2 + 1).isEmpty()) {
                accepted.add(workerIds.get(i));
            }
        }
        // Separate commit: failure leaves Binding intact for a registration retry.
        Set<String> created = accepted.isEmpty() ? Set.of()
                : scoreCore.initializeRegisteredScores(workerGroupId, accepted);
        Map<String, WorkerRegistrationResult> results = new LinkedHashMap<>();
        for (int i = 0; i < workerIds.size(); i++) {
            String workerId = workerIds.get(i);
            RegistrationStatus status = created.contains(workerId) ? RegistrationStatus.OK
                    : RegistrationStatus.valueOf(rows.get(i * 2));
            String endpoint = rows.get(i * 2 + 1);
            String reason = switch (status) {
                case NOT_FOUND -> "worker group not found";
                case INVALID -> "stored worker binding is invalid";
                case CONFLICT -> "workerId is already bound to another workerGroupId";
                default -> null;
            };
            results.put(workerId, new WorkerRegistrationResult(
                    status, endpoint.isEmpty() ? null : endpoint, reason
            ));
        }
        return results;
    }

    @Override
    public Map<String, WorkerGroupDescriptor> sampleWorkerGroupDescriptors(
            int sampleLimit
    ) {
        if (sampleLimit < 1
                || sampleLimit > MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT) {
            throw new IllegalArgumentException(
                    "sampleLimit must be between 1 and "
                            + MAX_WORKER_GROUP_DESCRIPTOR_SAMPLE_LIMIT
            );
        }
        return decodeGroups(commands().hrandfieldWithvalues(groupsKey(), sampleLimit));
    }

    @Override
    public Map<String, WorkerGroupDescriptor> getWorkerGroupDescriptors(
            List<String> workerGroupIds
    ) {
        requireIds(workerGroupIds, "workerGroupIds");
        if (workerGroupIds.isEmpty()) {
            return Map.of();
        }
        return decodeGroups(commands().hmget(
                groupsKey(),
                workerGroupIds.toArray(String[]::new)
        ));
    }

    @Override
    public Map<String, WorkerDescriptor> getWorkerDescriptors(List<String> workerIds) {
        requireWorkerIds(workerIds);
        if (workerIds.isEmpty()) {
            return Map.of();
        }
        return decodeBindings(commands().hmget(
                WorkerRedisSupport.bindingsKey(keyspace), workerIds.toArray(String[]::new)
        ));
    }

    @Override
    public CompletableFuture<Map<String, WorkerDescriptor>> getWorkerDescriptorsAsync(
            List<String> workerIds
    ) {
        requireWorkerIds(workerIds);
        if (workerIds.isEmpty()) {
            return CompletableFuture.completedFuture(Map.of());
        }
        return connection().async().hmget(
                WorkerRedisSupport.bindingsKey(keyspace), workerIds.toArray(String[]::new)
        ).thenApply(RedisWorkerResourceCatalog::decodeBindings).toCompletableFuture();
    }

    @Override
    public Map<String, WorkerDescriptor> sampleWorkerDescriptors(
            String workerGroupId,
            int sampleLimit
    ) {
        requireNonBlank(workerGroupId, "workerGroupId");
        if (sampleLimit < 1 || sampleLimit > MAX_WORKER_DESCRIPTOR_SAMPLE_LIMIT) {
            throw new IllegalArgumentException("sampleLimit must be between 1 and 100");
        }
        Map<String, WorkerDescriptor> sampled = getWorkerDescriptors(
                scoreCore.sampleRegisteredWorkerIds(workerGroupId, sampleLimit)
        );
        if (sampled.isEmpty()) {
            return sampled;
        }
        sampled.replaceAll((id, descriptor) -> descriptor != null
                && workerGroupId.equals(descriptor.workerGroupId()) ? descriptor : null);
        return sampled;
    }

    private static Map<String, WorkerDescriptor> decodeBindings(List<KeyValue<String, String>> rows) {
        Map<String, WorkerDescriptor> result = new LinkedHashMap<>();
        for (KeyValue<String, String> row : rows) {
            result.put(row.getKey(), WorkerRedisSupport.decodeBinding(
                    row.getKey(), row.getValueOrElse(null)
            ));
        }
        return result;
    }

    private static Map<String, WorkerGroupDescriptor> decodeGroups(List<KeyValue<String, String>> rows) {
        Map<String, WorkerGroupDescriptor> result = new LinkedHashMap<>();
        for (KeyValue<String, String> row : rows) {
            WorkerGroupDescriptor descriptor = WorkerRedisSupport.decodeWorkerGroup(row.getValueOrElse(null));
            result.put(row.getKey(), descriptor != null && row.getKey().equals(descriptor.workerGroupId())
                    ? descriptor : null);
        }
        return result;
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

    private String groupsKey() {
        return WorkerRedisSupport.groupsKey(keyspace);
    }

    private static RegistrationResult result(
            RegistrationStatus status,
            String reason
    ) {
        return new RegistrationResult(status, reason);
    }

    private static void requireIds(List<String> values, String name) {
        if (values == null) {
            throw new IllegalArgumentException(name + " must be present");
        }
        values.forEach(value -> requireNonBlank(value, name));
    }

    private static void requireWorkerIds(List<String> workerIds) {
        requireIds(workerIds, "workerIds");
        if (workerIds.size() > MAX_WORKER_BATCH_SIZE) {
            throw new IllegalArgumentException("workerIds must contain at most 100 IDs");
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isEmpty()) {
            throw new IllegalArgumentException(name + " must be non-empty");
        }
    }
}
