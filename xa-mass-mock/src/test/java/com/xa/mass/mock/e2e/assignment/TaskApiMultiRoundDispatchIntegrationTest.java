package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class TaskApiMultiRoundDispatchIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private WorkerManager workerManager;

    @Test
    void singleDeviceWithBatchSizeOneCompletesTaskAcrossMultipleRounds() throws Exception {
        String workerId = "round-worker-0";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, workerId);
        try {
            assertTrue(client.connectBlocking(), "Mock client failed to connect");

            String taskId = createTaskId(
                    "multi-round",
                    "single device multi round dispatch",
                    List.of("target-a", "target-b", "target-c"),
                    1
            );

            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=multi-round",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, approveResponse.get("success"));

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, ((Number) terminal.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(3, ((Number) terminal.task().get("taskSuccessNumber")).intValue());
            assertEquals(3, terminal.messages().size());

            for (Map<String, Object> message : terminal.messages()) {
                assertEquals("SUCCESS", message.get("status"));
                assertEquals(workerId, message.get("workerId"));
            }

            WorkerContext workerContext = workerManager.getWorkerContext(workerId);
            assertEquals(WorkerContextStatus.IDLE, workerContext.getStatus());
        } finally {
            client.disconnect();
        }
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
        workerManager.addWorkerContext(workerId, workerContext);
    }
}
