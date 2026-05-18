package com.xa.mass.starter;

import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskExecutionSpec;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.worker.WorkerManager;
import com.xa.mass.engine.worker.WorkerReachabilityState;
import com.xa.mass.engine.model.WorkerSchedulingCandidate;
import com.xa.mass.engine.model.WorkerSchedulingView;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.starter.config.EngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
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
        TaskCommandService taskCommands = config.getTaskCommandService();
        TaskQueryService taskQueries = config.getTaskQueryService();
        WorkerManager workerManager = config.getWorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.updateHeartbeat();
        workerManager.addWorker(worker);

        config.setMatchingStrategy((task, maxWorkerCount) -> {
            if (!workerManager.tryLockWorker("worker-1")) {
                return List.of();
            }
            return List.of(new WorkerSchedulingCandidate(
                    workerManager.getWorker("worker-1"),
                    WorkerSchedulingView.from(
                            workerManager.getWorker("worker-1"),
                            WorkerReachabilityState.ONLINE,
                            true,
                            true
                    )
            ));
        });

        TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
        dto.setUserId("user-1");
        dto.setProject("demoApp");
        dto.setSourceRef("startup-recovery");
        dto.setSharedConfig(Map.of());
        dto.setExecutionSpec(taskExecutionSpec(1, 3));

        Task task = taskCommands.createTaskShell(dto);
        taskCommands.appendTaskItems(task.getTid(), List.of(Map.of("payload", "hello")));
        assertTrue(taskCommands.sealTask(task.getTid()));
        assertTrue(taskCommands.approveTask(task.getTid()));

        Task runningTask = taskQueries.getTask(task.getTid());
        assertNotNull(runningTask);
        assertTrue(runningTask.transitionTo(TaskStatus.RUNNING));
        assertTrue(taskCommands.updateTask(runningTask));

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

            com.xa.mass.storage.api.TaskDetailStore.TaskMessageProjection message =
                    config.getTaskDetailStore().getTaskMessageProjections(task.getTid(), 1).get(0);
            assertNotNull(message);
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
            TaskCommandService taskCommands = config.getTaskCommandService();
            TaskQueryService taskQueries = config.getTaskQueryService();
            TaskResultIngestFacade resultIngestFacade = config.getTaskResultIngestFacade();
            WorkerManager workerManager = config.getWorkerManager();

            Worker worker = new Worker();
            worker.setWorkerId("worker-1");
            worker.setSupportedProjects(List.of("demoApp"));
            worker.updateHeartbeat();
            workerManager.addWorker(worker);

            config.setMatchingStrategy((task, maxWorkerCount) -> {
                if (!workerManager.tryLockWorker("worker-1")) {
                    return List.of();
                }
                return List.of(new WorkerSchedulingCandidate(
                        workerManager.getWorker("worker-1"),
                        WorkerSchedulingView.from(
                                workerManager.getWorker("worker-1"),
                                WorkerReachabilityState.ONLINE,
                                true,
                                true
                        )
                ));
            });
            config.setRuntimeReadyDispatchIntervalMillis(50L);

            TaskShellCreateRequestDto dto = new TaskShellCreateRequestDto();
            dto.setUserId("user-1");
            dto.setProject("demoApp");
            dto.setSourceRef("batch-delayed-retry-recovery");
            dto.setSharedConfig(Map.of());
            dto.setExecutionSpec(taskExecutionSpec(1, 3));

            Task task = taskCommands.createTaskShell(dto);
            taskCommands.appendTaskItems(task.getTid(), List.of(Map.of("payload", "hello")));
            assertTrue(taskCommands.sealTask(task.getTid()));
            assertTrue(taskCommands.approveTask(task.getTid()));

            Task runningTask = taskQueries.getTask(task.getTid());
            assertNotNull(runningTask);
            assertTrue(runningTask.transitionTo(TaskStatus.RUNNING));
            assertTrue(taskCommands.updateTask(runningTask));

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

    private static TaskExecutionSpec taskExecutionSpec(int batchSize, int defaultMaxRetryCount) {
        TaskExecutionSpec spec = new TaskExecutionSpec();
        spec.setBatchSize(batchSize);
        spec.setDefaultMaxRetryCount(defaultMaxRetryCount);
        return spec;
    }

    private static void restoreProperty(String key, String previousValue) {
        if (previousValue == null) {
            System.clearProperty(key);
        } else {
            System.setProperty(key, previousValue);
        }
    }
}

