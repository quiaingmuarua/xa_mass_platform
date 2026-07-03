package com.xa.mass.starter;

import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.redis.RedisWorkerRegistry;
import com.xa.mass.task.runtime.AppendBatchOutcome;
import com.xa.mass.task.runtime.AppendBatchStatus;
import com.xa.mass.task.runtime.AppendItemInput;
import com.xa.mass.task.runtime.command.TaskRuntimeCommandPort;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceDeclarationRuntime;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.starter.config.EngineConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerRuntimeSelectionIntegrationTest {

    private static final String DEFAULT_REDIS_URI = "redis://127.0.0.1:6379/0";

    @Test
    void memoryWorkerRegistryDispatchesThroughDefaultEngineMatchPath() throws Exception {
        runDispatchProof(new InMemoryWorkerRegistry());
    }

    @Test
    void redisWorkerRegistryDispatchesThroughDefaultEngineMatchPathWhenRedisAvailable() throws Exception {
        String redisUri = System.getProperty("mass.redis.test.uri", DEFAULT_REDIS_URI);
        String namespace = "xa:mass:test:worker-runtime-selection:" + UUID.randomUUID();
        assumeRedisAvailable(redisUri);
        RedisWorkerRegistry registry = new RedisWorkerRegistry(redisUri, namespace);
        try {
            runDispatchProof(registry);
        } finally {
            registry.close();
            cleanupRedisNamespace(redisUri, namespace);
        }
    }

    private static void runDispatchProof(WorkerRegistry workerRegistry) throws Exception {
        EngineConfig config = new EngineConfig();
        config.setWorkerRegistry(workerRegistry);
        config.setWorkerReachabilityLookup(workerId -> WorkerReachabilityState.ONLINE);
        config.setRuntimeReadyDispatchIntervalMillis(50L);

        WorkerResourceDeclarationRuntime workerDeclaration = config.getWorkerResourceDeclarationRuntime();
        workerDeclaration.upsertWorkerGroup(WorkerGroupRecord.builder("wrx-selection-workers")
                .projectCodes(Set.of("demoApp"))
                .build());
        workerDeclaration.addWorker(workerDeclaration("wrx-worker-1", "wrx-selection-workers"));
        long observedAtMillis = System.currentTimeMillis();
        config.getWorkerHeartbeatRuntime().refreshWorkerHeartbeat("wrx-worker-1", observedAtMillis);

        CountDownLatch dispatchLatch = new CountDownLatch(1);
        AtomicReference<String> taskIdRef = new AtomicReference<>();
        AtomicReference<List<TaskDispatchBinding>> dispatchBindingsRef = new AtomicReference<>();
        MassEngine engine = new MassEngine(config);
        try {
            engine.start((dispatchedTask, dispatchBindings) -> {
                if (taskIdRef.get() != null && taskIdRef.get().equals(dispatchedTask.taskId())) {
                    dispatchBindingsRef.set(dispatchBindings);
                    dispatchLatch.countDown();
                }
            });

            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setUserId("user-wrx");
            dto.setProject("demoApp");
            dto.setSourceRef("wrx-runtime-selection");
            dto.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "wrx-selection-workers"));
            dto.setExecutionSpec(taskExecutionSpec());

            String taskId = UUID.randomUUID().toString();
            TaskCommandOutcome create = config.createTaskShellDescriptor(dto, taskId);
            assertTrue(create.accepted());
            TaskRuntimeCommandPort taskCommands = config.getTaskRuntimeCommandPort();
            assertTrue(taskCommands.create(taskId).accepted());
            taskIdRef.set(taskId);
            AppendBatchOutcome append = taskCommands.append(
                    taskId,
                    List.of(new AppendItemInput("wrx-message-1", Map.of("payload", "runtime-selection"))),
                    10);
            assertEquals(AppendBatchStatus.ALL_ACCEPTED, append.status());
            assertTrue(taskCommands.approve(taskId).accepted());

            assertTrue(dispatchLatch.await(5, TimeUnit.SECONDS));
            List<TaskDispatchBinding> dispatchBindings = dispatchBindingsRef.get();
            assertNotNull(dispatchBindings);
            assertEquals(1, dispatchBindings.size());
            TaskDispatchBinding binding = dispatchBindings.get(0);
            assertEquals(taskId, binding.taskId());
            assertEquals("wrx-worker-1", binding.workerId());
            assertEquals("wrx-selection-workers", binding.workerGroupId());
            assertEquals(Map.of("payload", "runtime-selection"), binding.payload());
        } finally {
            engine.stop();
        }
    }

    private static WorkerDeclarationRecord workerDeclaration(String workerId, String workerGroupId) {
        return new WorkerDeclarationRecord(
                workerId,
                workerGroupId,
                "polling",
                null,
                1,
                Map.of()
        );
    }

    private static TaskExecutionSpec taskExecutionSpec() {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(1);
        spec.setDefaultMaxRetryCount(1);
        return spec;
    }

    private static void assumeRedisAvailable(String redisUri) {
        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            connection.sync().ping();
        } catch (RuntimeException ex) {
            Assumptions.assumeTrue(false, "Redis is not available for worker runtime selection proof: "
                    + ex.getMessage());
        } finally {
            client.shutdown();
        }
    }

    private static void cleanupRedisNamespace(String redisUri, String namespace) {
        RedisClient client = RedisClient.create(redisUri);
        try (StatefulRedisConnection<String, String> connection = client.connect()) {
            RedisCommands<String, String> commands = connection.sync();
            List<String> keys = commands.keys(namespace + ":*");
            if (!keys.isEmpty()) {
                commands.del(keys.toArray(String[]::new));
            }
        } finally {
            client.shutdown();
        }
    }
}
