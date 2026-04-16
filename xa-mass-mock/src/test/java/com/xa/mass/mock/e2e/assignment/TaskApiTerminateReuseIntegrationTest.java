package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiTerminateReuseIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private WorkerManager workerManager;

    @Test
    void terminatedTaskReleasesSingleDeviceForNextTask() throws Exception {
        String workerId = "terminate-reuse-worker-0";
        registerWorker(workerId);

        String firstTaskId = createTaskId("terminate-reuse-first", "terminate reuse first", "target-a");
        Map<String, Object> firstApprove = audit(firstTaskId, "terminate-reuse-1");
        assertEquals(Boolean.TRUE, firstApprove.get("success"));

        TaskSnapshot firstRunning = waitForTaskSnapshot(firstTaskId, "RUNNING", 20, 500L);
        assertEquals(workerId, firstRunning.messages().get(0).get("workerId"));

        Map<String, Object> firstTerminate = exchange(
                "/status/api/tasks/" + firstTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, firstTerminate.get("success"));

        TaskSnapshot firstTerminal = waitForTaskSnapshot(firstTaskId, "TERMINAL", 20, 500L);
        assertEquals("EXPIRED", firstTerminal.messages().get(0).get("status"));
        assertEquals(WorkerContextStatus.IDLE, workerManager.getWorkerContexts(workerId).get(0).getStatus());

        String secondTaskId = createTaskId("terminate-reuse-second", "terminate reuse second", "target-b");
        Map<String, Object> secondApprove = audit(secondTaskId, "terminate-reuse-2");
        assertEquals(Boolean.TRUE, secondApprove.get("success"));

        TaskSnapshot secondRunning = waitForTaskSnapshot(secondTaskId, "RUNNING", 20, 500L);
        assertEquals(workerId, secondRunning.messages().get(0).get("workerId"));

        Map<String, Object> secondTerminate = exchange(
                "/status/api/tasks/" + secondTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, secondTerminate.get("success"));
        waitForTaskSnapshot(secondTaskId, "TERMINAL", 20, 500L);
        assertEquals(WorkerContextStatus.IDLE, workerManager.getWorkerContexts(workerId).get(0).getStatus());
    }

    private void registerWorker(String workerId) {
        Worker worker = new Worker();
        worker.setWorkerId(workerId);
        worker.setWorkerGroupId("us");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        workerManager.addWorker(worker);

        WorkerContext workerContext = new WorkerContext();
        workerContext.setWorkerContextId("worker-context-" + workerId);
        workerContext.setWorkerId(workerId);
        workerContext.setChannel("us");
        workerContext.setStatus(WorkerContextStatus.IDLE);
        workerManager.addWorkerContext(workerContext);
    }
}
