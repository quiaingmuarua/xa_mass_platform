package com.xa.mass.server.e2e.assignment;

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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

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
class TaskApiWorkerWithoutContextIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void workerWithoutContextCanExecuteTaskWhenTaskHasNoRoutingRequirement() throws Exception {
        registerSdkStatelessWorker("stateless-worker", "demoApp");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(uri, "stateless-worker");
        try {
            assertClientConnects(client, "stateless worker client failed to connect");

            Map<String, Object> createBody = new LinkedHashMap<>();
            createBody.put("taskName", "worker-without-context");
            createBody.put("project", "demoApp");
            createBody.put("sharedConfig", Map.of("textContent", "stateless dispatch integration"));
            createBody.put("userId", "itest");
            createBody.put("batchSize", 1);

            Map<String, Object> createResponse = createTaskShell(createBody);
            assertApiOk(createResponse);
            String taskId = String.valueOf(responseData(createResponse).get("taskId"));
            assertApiOk(appendTaskItems(taskId, List.of(Map.of("target", "target-a")), 3));
            assertApiOk(sealTask(taskId));

            Map<String, Object> auditResponse = approveTask(taskId);
            assertApiOk(auditResponse);

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, terminalSnapshot.messages().size());

            Map<String, Object> message = terminalSnapshot.messages().get(0);
            assertEquals("stateless-worker", message.get("latestAttemptWorkerId"));
            assertNull(message.get("latestAttemptWorkerContextId"));
            assertEquals("SUCCESS", message.get("status"));
            assertNotNull(message.get("latestAttemptBatchId"));
        } finally {
            client.disconnect();
        }
    }
}

