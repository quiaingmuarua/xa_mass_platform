package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ProjectionSampleE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BooleanSupplier;

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=true",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "sample.client.retry-attempts=1",
                "sample.client.retry-delay=1",
                "sample.client.connection-timeout=5",
                "sample.client.ping-delay=60",
                "sample.client.ping-interval=60"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiTargetedWorkerDebugIntegrationTest extends ProjectionSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String STATE_WORKER_ID = "it-worker-0";
    private static final String DISCONNECT_WORKER_ID = "it-worker-1";
    private static final String TARGET_WORKER_ID_KEY = "targetWorkerId";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    void targetedMockDebugTasksFlowThroughTaskMainline() throws Exception {
        String delayTaskId = createTargetedDebugTask(
                "targeted-delay-response",
                "mock.delay.response",
                STATE_WORKER_ID,
                Map.of("millis", 400)
        );
        assertApiOk(audit(delayTaskId, "targeted-delay-response"));
        TaskSnapshot delayTerminal = waitForTerminalTask(delayTaskId);
        assertEquals("TERMINAL", delayTerminal.task().get("status"));
        assertEquals(1, delayTerminal.messages().size());
        Map<String, Object> delayMessage = delayTerminal.messages().get(0);
        assertEquals("SUCCESS", delayMessage.get("status"));
        assertEquals(STATE_WORKER_ID, delayMessage.get("latestAttemptWorkerId"));
        Map<String, Object> delayOutput = output(delayMessage);
        assertEquals("mock.delay.response", delayOutput.get("eventCode"));
        assertEquals("delay_updated", delayOutput.get("action"));
        assertEquals(400, ((Number) map(delayOutput.get("state")).get("taskResponseDelayMillis")).intValue());

        String stateTaskId = createTargetedDebugTask(
                "targeted-state-get",
                "mock.state.get",
                STATE_WORKER_ID,
                Map.of()
        );
        assertApiOk(audit(stateTaskId, "targeted-state-get"));
        TaskSnapshot stateTerminal = waitForTerminalTask(stateTaskId);
        Map<String, Object> stateMessage = stateTerminal.messages().get(0);
        assertEquals("SUCCESS", stateMessage.get("status"));
        assertEquals(STATE_WORKER_ID, stateMessage.get("latestAttemptWorkerId"));
        Map<String, Object> stateOutput = output(stateMessage);
        assertEquals("mock.state.get", stateOutput.get("eventCode"));
        assertEquals("state", stateOutput.get("action"));
        assertEquals(400, ((Number) map(stateOutput.get("state")).get("taskResponseDelayMillis")).intValue());
    }

    @Test
    void targetedDisconnectTaskDisconnectsWorkerAfterTaskResult() throws Exception {
        String taskId = createTargetedDebugTask(
                "targeted-disconnect",
                "mock.disconnect",
                DISCONNECT_WORKER_ID,
                Map.of()
        );
        assertApiOk(audit(taskId, "targeted-disconnect"));

        TaskSnapshot terminal = waitForTerminalTask(taskId);
        assertEquals("TERMINAL", terminal.task().get("status"));
        assertEquals(1, terminal.messages().size());

        Map<String, Object> message = terminal.messages().get(0);
        assertEquals("SUCCESS", message.get("status"));
        assertEquals(DISCONNECT_WORKER_ID, message.get("latestAttemptWorkerId"));

        Map<String, Object> output = output(message);
        assertEquals("mock.disconnect", output.get("eventCode"));
        assertEquals("disconnect", output.get("action"));
        assertEquals(Boolean.TRUE, output.get("disconnectAfterAck"));
        assertEquals(DISCONNECT_WORKER_ID, output.get("disconnectWorkerId"));

        waitUntil(() -> !hasActiveEndpoint(DISCONNECT_WORKER_ID),
                "targeted disconnect task must remove the worker endpoint after result");
    }

    private String createTargetedDebugTask(String taskName,
                                           String eventCode,
                                           String workerId,
                                           Map<String, Object> input) {
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "demoApp");
        createBody.put("userId", "itest");
        createBody.put("sharedConfig", Map.of(TARGET_WORKER_ID_KEY, workerId));
        createBody.put("sourceRef", taskName);
        createBody.put("executionSpec", Map.of("batchSize", 1));

        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, eventCode, List.of(input)));
        assertApiOk(sealTask(taskId));
        return taskId;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> output(Map<String, Object> message) {
        Object output = message.get("output");
        assertInstanceOf(Map.class, output);
        return (Map<String, Object>) output;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        assertInstanceOf(Map.class, value);
        return (Map<String, Object>) value;
    }

    private void waitUntil(BooleanSupplier condition, String failureMessage) throws InterruptedException {
        for (int attempt = 0; attempt < 20; attempt++) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(100L);
        }
        assertTrue(condition.getAsBoolean(), failureMessage);
    }

    @SuppressWarnings("unchecked")
    private boolean hasActiveEndpoint(String workerId) {
        Map<String, Object> response = exchange("/api/v1/catalog/worker-capabilities", HttpMethod.GET, null);
        assertApiOk(response);
        Object data = response.get("data");
        List<Map<String, Object>> items = data instanceof List<?> list ? (List<Map<String, Object>>) list : null;
        if (items == null) {
            return false;
        }
        return items.stream()
                .filter(item -> workerId.equals(String.valueOf(item.get("workerId"))))
                .anyMatch(item -> Boolean.TRUE.equals(item.get("hasActiveEndpoint")));
    }
}
