package com.xa.mass.starter;

import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandPort;
import com.xa.mass.engine.model.TaskCommandOutcome;
import com.xa.mass.worker.runtime.resource.WorkerDeclarationRecord;
import com.xa.mass.worker.runtime.resource.WorkerResourceDeclarationRuntime;
import com.xa.mass.worker.runtime.resource.WorkerGroupRecord;
import com.xa.mass.worker.runtime.evidence.WorkerReachabilityState;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.storage.memory.InMemoryTaskShellStore;
import org.junit.jupiter.api.Test;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MassEngineStartRecoveryTest {

    @Test
    void startRecoversRunningTaskWithRuntimeReadyWork() throws Exception {
        EngineConfig config = new EngineConfig();
        InMemoryTaskShellStore taskStore = new InMemoryTaskShellStore();
        config.setTaskShellStore(taskStore);
        registerSelectableWorker(config, "worker-1", "demo-workers");

        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setUserId("user-1");
        dto.setProject("demoApp");
        dto.setSourceRef("startup-recovery");
        dto.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "demo-workers"));
        dto.setExecutionSpec(taskExecutionSpec(1, 3));

        Task task = createApprovedRuntimeTask(config, taskStore, dto, "startup-message-1");

        Task runningTask = taskStore.getTask(task.getTid()).orElse(null);
        assertNotNull(runningTask);
        assertTrue(runningTask.transitionTo(TaskStatus.RUNNING));
        assertTrue(taskStore.updateTask(runningTask));

        CountDownLatch dispatchLatch = new CountDownLatch(1);
        AtomicReference<List<TaskDispatchBinding>> dispatchBindingsRef = new AtomicReference<>();
        MassEngine engine = new MassEngine(config);

        try {
            engine.start((dispatchedTask, dispatchBindings) -> {
                if (task.getTid().equals(dispatchedTask.taskId())) {
                    dispatchBindingsRef.set(dispatchBindings);
                    dispatchLatch.countDown();
                }
            });

            assertTrue(dispatchLatch.await(5, TimeUnit.SECONDS));

            List<TaskDispatchBinding> dispatchBindings = dispatchBindingsRef.get();
            assertNotNull(dispatchBindings);
            assertEquals(1, dispatchBindings.size());
            assertEquals(task.getTid(), dispatchBindings.get(0).taskId());
            assertEquals("worker-1", dispatchBindings.get(0).workerId());
            assertEquals(Map.of("payload", "hello"), dispatchBindings.get(0).payload());
        } finally {
            engine.stop();
        }
    }

    @Test
    void batchRetryWithDelayedRuntimeVisibilityIsRecoveredByRuntimePump() throws Exception {
        String previousBulkRetryDelay = System.getProperty("xa.mass.engine.bulkWorkRetryDelayMillis");
        try {
            System.setProperty("xa.mass.engine.bulkWorkRetryDelayMillis", "200");

            EngineConfig config = new EngineConfig();
            InMemoryTaskShellStore taskStore = new InMemoryTaskShellStore();
            config.setTaskShellStore(taskStore);
            TaskResultIngestFacade resultIngestFacade = config.getTaskResultIngestFacade();
            registerSelectableWorker(config, "worker-1", "demo-workers");
            config.setRuntimeReadyDispatchIntervalMillis(50L);

            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setUserId("user-1");
            dto.setProject("demoApp");
            dto.setSourceRef("batch-delayed-retry-recovery");
            dto.setSharedConfig(Map.of(TaskSharedConfig.WORKER_GROUP_ID, "demo-workers"));
            dto.setExecutionSpec(taskExecutionSpec(1, 3));

            Task task = createApprovedRuntimeTask(config, taskStore, dto, "retry-message-1");

            Task runningTask = taskStore.getTask(task.getTid()).orElse(null);
            assertNotNull(runningTask);
            assertTrue(runningTask.transitionTo(TaskStatus.RUNNING));
            assertTrue(taskStore.updateTask(runningTask));

            CountDownLatch firstDispatchLatch = new CountDownLatch(1);
            CountDownLatch secondDispatchLatch = new CountDownLatch(1);
            AtomicReference<TaskDispatchBinding> firstDispatchRef = new AtomicReference<>();
            AtomicReference<TaskDispatchBinding> secondDispatchRef = new AtomicReference<>();
            AtomicReference<String> firstAttemptId = new AtomicReference<>();
            AtomicReference<String> secondAttemptId = new AtomicReference<>();
            MassEngine engine = new MassEngine(config);

            try {
                engine.start((dispatchedTask, dispatchBindings) -> {
                    if (!task.getTid().equals(dispatchedTask.taskId()) || dispatchBindings == null || dispatchBindings.isEmpty()) {
                        return;
                    }
                    TaskDispatchBinding binding = dispatchBindings.get(0);
                    if (firstDispatchRef.compareAndSet(null, binding)) {
                        firstAttemptId.set(binding.attemptId());
                        firstDispatchLatch.countDown();
                        return;
                    }
                    if (secondDispatchRef.compareAndSet(null, binding)) {
                        secondAttemptId.set(binding.attemptId());
                        secondDispatchLatch.countDown();
                    }
                });

                assertTrue(firstDispatchLatch.await(5, TimeUnit.SECONDS));
                TaskDispatchBinding firstDispatch = firstDispatchRef.get();
                assertNotNull(firstDispatch);

                assertTrue(resultIngestFacade.ingestTaskResult(
                        task.getTid(),
                        firstDispatch.messageId(),
                        false,
                        "boom-once",
                        "SYNTHETIC_RETRY",
                        Map.of("outcome", "retry")
                ));

                assertTrue(secondDispatchLatch.await(5, TimeUnit.SECONDS));
                TaskDispatchBinding secondDispatch = secondDispatchRef.get();
                assertNotNull(secondDispatch);
                assertEquals(task.getTid(), secondDispatch.taskId());
                assertEquals(firstDispatch.messageId(), secondDispatch.messageId());
                assertEquals(1, secondDispatch.retryCount());
                assertTrue(secondAttemptId.get() != null && !secondAttemptId.get().equals(firstAttemptId.get()));
            } finally {
                engine.stop();
            }
        } finally {
            restoreProperty("xa.mass.engine.bulkWorkRetryDelayMillis", previousBulkRetryDelay);
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

    private static void registerSelectableWorker(EngineConfig config, String workerId, String workerGroupId) {
        config.setWorkerReachabilityLookup(candidateWorkerId -> WorkerReachabilityState.ONLINE);
        WorkerResourceDeclarationRuntime workerDeclaration = config.getWorkerResourceDeclarationRuntime();
        workerDeclaration.upsertWorkerGroup(WorkerGroupRecord.builder(workerGroupId)
                .projectCodes(Set.of("demoApp"))
                .build());
        workerDeclaration.addWorker(workerDeclaration(workerId, workerGroupId));
        long observedAtMillis = System.currentTimeMillis();
        config.getWorkerHeartbeatRuntime().refreshWorkerHeartbeat(workerId, observedAtMillis);
    }

    private static TaskExecutionSpec taskExecutionSpec(int batchSize, int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(batchSize);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private static Task createApprovedRuntimeTask(EngineConfig config,
                                                  InMemoryTaskShellStore taskStore,
                                                  TaskShellCreateRequestDto dto,
                                                  String messageId) {
        TaskCommandPort taskCommands = internalTaskCommandPort(config);
        TaskCommandOutcome create = taskCommands.createTaskShell(dto);
        assertTrue(create.accepted());
        Task task = taskStore.getTask(create.taskId()).orElse(null);
        assertNotNull(task);
        assertTrue(taskCommands.appendTaskItems(task.getTid(), List.of(Map.of("payload", "hello"))).accepted());
        assertTrue(taskCommands.sealTask(task.getTid()).accepted());
        assertTrue(taskCommands.approveTask(task.getTid()).accepted());
        return task;
    }

    private static TaskCommandPort internalTaskCommandPort(EngineConfig config) {
        try {
            Method method = EngineConfig.class.getDeclaredMethod("taskCommandPort");
            method.setAccessible(true);
            return (TaskCommandPort) method.invoke(config);
        } catch (ReflectiveOperationException e) {
            throw new IllegalStateException("Failed to reach old engine command quarantine port", e);
        }
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }
}
