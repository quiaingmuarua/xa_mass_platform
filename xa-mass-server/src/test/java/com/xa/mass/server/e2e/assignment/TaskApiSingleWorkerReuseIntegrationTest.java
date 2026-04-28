package com.xa.mass.server.e2e.assignment;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.sample.client.MockWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiSingleWorkerReuseIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void singleWorkerCanBeReusedAfterPreviousTaskCompletes() throws Exception {
        String workerId = "reuse-worker-0";
        registerWorker(workerId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MockWorkerWebSocketClient client = new MockWorkerWebSocketClient(wsUri, workerId);
        try {
            assertClientConnects(client, "Mock client failed to connect");

            String firstTaskId = createTaskId("reuse-first", "single worker reuse first", "target-a");
            Map<String, Object> firstApprove = exchange(
                    "/status/api/tasks/" + firstTaskId + "/audit?approved=true&comment=single-worker-reuse-1",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(firstApprove);
            TaskSnapshot firstTerminal = waitForTaskSnapshot(firstTaskId, "TERMINAL", 20, 500L);
        assertEquals(workerId, firstTerminal.messages().get(0).get("latestAttemptWorkerId"));

            String secondTaskId = createTaskId("reuse-second", "single worker reuse second", "target-b");
            Map<String, Object> secondApprove = exchange(
                    "/status/api/tasks/" + secondTaskId + "/audit?approved=true&comment=single-worker-reuse-2",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(secondApprove);
            TaskSnapshot secondTerminal = waitForTaskSnapshot(secondTaskId, "TERMINAL", 20, 500L);
        assertEquals(workerId, secondTerminal.messages().get(0).get("latestAttemptWorkerId"));

            WorkerContext workerContext = app.getWorkerContexts(workerId).get(0);
            assertEquals(WorkerContextStatus.IDLE, workerContext.getStatus());
        } finally {
            client.disconnect();
        }
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }
}
