package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.worker.WorkerStatus;
import com.xa.mass.base.model.Worker;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
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
class TaskApiWorkerWithoutContextIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private WorkerManager workerManager;

    @Test
    void workerWithoutContextCanExecuteTaskWhenTaskHasNoRoutingRequirement() throws Exception {
        Worker worker = new Worker();
        worker.setWorkerId("stateless-worker");
        worker.setWorkerGroupId("pool-a");
        worker.setStatus(WorkerStatus.ONLINE);
        worker.setSupportedProjects(List.of("demoApp"));
        workerManager.addWorker(worker);

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(uri, "stateless-worker");
        try {
            assertClientConnects(client, "stateless worker client failed to connect");

            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("taskName", "worker-without-context");
            createBody.put("project", "demoApp");
            createBody.put("sharedConfig", Map.of("textContent", "stateless dispatch integration"));
            createBody.put("userId", "itest");
            createBody.put("targetList", List.of("target-a"));
            createBody.put("batchSize", 1);

            Map<String, Object> createResponse = exchange("/status/api/tasks", HttpMethod.POST, createBody);
            assertEquals(Boolean.TRUE, createResponse.get("success"));
            String taskId = String.valueOf(createResponse.get("taskId"));

            Map<String, Object> auditResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=worker-without-context",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, auditResponse.get("success"));

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, terminalSnapshot.messages().size());

            Map<String, Object> message = terminalSnapshot.messages().get(0);
            assertEquals("stateless-worker", message.get("workerId"));
            assertNull(message.get("workerContextId"));
            assertEquals("SUCCESS", message.get("status"));
            assertNotNull(message.get("batchId"));
        } finally {
            client.disconnect();
        }
    }
}
