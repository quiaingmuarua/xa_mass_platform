package com.xa.mass.starter;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBinding;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskQueryService;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.model.MatchedWorkerContext;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.starter.config.EngineConfig;
import org.junit.jupiter.api.Test;

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
        TaskCommandService taskCommands = config.getTaskCommandService();
        TaskQueryService taskQueries = config.getTaskQueryService();
        WorkerManager workerManager = config.getWorkerManager();

        Worker worker = new Worker();
        worker.setWorkerId("worker-1");
        worker.setSupportedProjects(List.of("demoApp"));
        worker.updateHeartbeat();
        workerManager.addWorker(worker);

        WorkerContext workerContext = new WorkerContext("wctx-1", "worker-1", Set.of("default"));
        workerManager.addWorkerContext(workerContext);

        config.setMatchingStrategy((task, maxWorkerCount) -> {
            if (!workerManager.tryLockWorker("worker-1")) {
                return List.of();
            }
            return List.of(new MatchedWorkerContext(
                    workerManager.getWorker("worker-1"),
                    workerManager.getWorkerContextById("wctx-1")
            ));
        });

        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setUserId("user-1");
        dto.setProject("demoApp");
        dto.setTaskName("startup-recovery");
        dto.setInputs(List.of(Map.of("payload", "hello")));
        dto.setSharedConfig(Map.of());
        dto.setBatchSize(1);

        Task task = taskCommands.createTask(dto);
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
                if (task.getTid().equals(dispatchedTask.getTid())) {
                    dispatchBindingsRef.set(dispatchBindings);
                    dispatchLatch.countDown();
                }
            });

            assertTrue(dispatchLatch.await(5, TimeUnit.SECONDS));

            List<TaskDispatchBinding> dispatchBindings = dispatchBindingsRef.get();
            assertNotNull(dispatchBindings);
            assertEquals(1, dispatchBindings.size());
            assertEquals(task.getTid(), dispatchBindings.get(0).taskMsg().getTaskId());
            assertEquals("worker-1", dispatchBindings.get(0).attempt().getWorkerId());

            TaskMsg message = taskQueries.getTaskMessages(task.getTid(), 1).get(0);
            assertEquals(TaskMsgStatus.ASSIGNED, message.getStatus());
            assertNotNull(taskQueries.getLatestActiveTaskMessageAttempt(task.getTid(), message.getMessageId()));
        } finally {
            engine.stop();
        }
    }
}
