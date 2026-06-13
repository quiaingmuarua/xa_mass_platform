package com.xa.mass.starter;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.runtime.memory.InMemoryWorkerRegistry;
import com.xa.mass.runtime.redis.RedisWorkerRegistry;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.runtime.worker.WorkerRegistry;
import com.xa.mass.worker.runtime.resource.WorkerResourceRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceRuntime;
import com.xa.mass.starter.config.EngineConfig;
import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.api.sync.RedisCommands;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
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
        config.setRuntimeReadyDispatchIntervalMillis(50L);

        WorkerResourceRuntime workerResources = config.getWorkerResourceRuntime();
        workerResources.upsertWorkerGroup(WorkerGroupRecord.builder("wrx-selection-workers")
                .projectCodes(Set.of("demoApp"))
                .build());
        workerResources.addWorker(workerResource("wrx-worker-1", "wrx-selection-workers"));
        config.getWorkerPresenceRuntime().sessionConnected(
                "wrx-worker-1",
                "polling",
                "wrx-selection-workers",
                "wrx-session-1",
                System.currentTimeMillis(),
                "test worker session connected"
        );

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

            TaskCommandService taskCommands = config.getTaskCommandService();
            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setUserId("user-wrx");
            dto.setProject("demoApp");
            dto.setSourceRef("wrx-runtime-selection");
            dto.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "wrx-selection-workers"));
            dto.setExecutionSpec(taskExecutionSpec());

            Task task = taskCommands.createTaskShell(dto);
            taskIdRef.set(task.getTid());
            taskCommands.appendTaskItems(task.getTid(), List.of(Map.of("payload", "runtime-selection")));
            assertTrue(taskCommands.sealTask(task.getTid()));
            assertTrue(taskCommands.approveTask(task.getTid()));

            assertTrue(dispatchLatch.await(5, TimeUnit.SECONDS));
            List<TaskDispatchBinding> dispatchBindings = dispatchBindingsRef.get();
            assertNotNull(dispatchBindings);
            assertEquals(1, dispatchBindings.size());
            TaskDispatchBinding binding = dispatchBindings.get(0);
            assertEquals(task.getTid(), binding.taskId());
            assertEquals("wrx-worker-1", binding.workerId());
            assertEquals("wrx-selection-workers", binding.workerGroupId());
            assertEquals(Map.of("payload", "runtime-selection"), binding.payload());
        } finally {
            engine.stop();
        }
    }

    private static WorkerResourceRecord workerResource(String workerId, String workerGroupId) {
        return new WorkerResourceRecord(
                workerId,
                "ONLINE",
                null,
                LocalDateTime.now(),
                List.of("demoApp"),
                List.of(),
                workerGroupId,
                null,
                null,
                null,
                1,
                Map.of(),
                null,
                null
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
