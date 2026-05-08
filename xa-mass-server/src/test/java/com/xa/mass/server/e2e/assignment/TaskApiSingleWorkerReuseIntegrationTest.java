package com.xa.mass.server.e2e.assignment;

import com.xa.mass.base.enums.worker.WorkerContextStatus;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
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
class TaskApiSingleWorkerReuseIntegrationTest extends AbstractSampleE2eTest {

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
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(wsUri, workerId);
        try {
            assertClientConnects(client, "Sample client failed to connect");

            String firstTaskId = createTaskId("reuse-first", "single worker reuse first", "target-a");
            Map<String, Object> firstApprove = exchange(
                    "/api/v1/tasks/" + firstTaskId + ":approve",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(firstApprove);
            TaskSnapshot firstTerminal = waitForTaskSnapshot(firstTaskId, "TERMINAL", 20, 500L);
        assertEquals(workerId, firstTerminal.messages().get(0).get("latestAttemptWorkerId"));

            String secondTaskId = createTaskId("reuse-second", "single worker reuse second", "target-b");
            Map<String, Object> secondApprove = exchange(
                    "/api/v1/tasks/" + secondTaskId + ":approve",
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

