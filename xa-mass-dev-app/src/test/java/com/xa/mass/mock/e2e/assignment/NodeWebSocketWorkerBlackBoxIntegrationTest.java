package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import com.xa.mass.mock.e2e.support.ExternalNodeWorkerProcess;
import com.xa.mass.base.model.Worker;
import org.junit.jupiter.api.Test;
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
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Black-box proof that a non-JVM worker can participate through the public
 * WebSocket transport boundary once the worker resource is registered.
 *
 * <p>The worker identity is established at WebSocket handshake time and
 * both dispatch and result write-back use canonical root-level task frames.
 */
@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.workers=mock/test_node_realtime_worker.json",
                "mass.mock.data.worker-contexts=mock/test_node_realtime_worker_context.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class NodeWebSocketWorkerBlackBoxIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "node-realtime-worker-001";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void externalNodeWorkerCompletesTaskThroughHandshakeIdentityAndCanonicalResult() throws Exception {
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        try (ExternalNodeWorkerProcess worker = ExternalNodeWorkerProcess.start(WORKER_ID, wsUri)) {
            waitForWorkerStatus(WORKER_ID, "ONLINE", worker);

            String taskId = createTaskId(
                    "cross-language-node-worker",
                    "cross-language black-box worker",
                    List.of("target-node-001"),
                    1
            );
            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=node-black-box",
                    HttpMethod.POST,
                    null
            );
            assertApiOk(approveResponse);

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(WORKER_ID, terminal.messages().get(0).get("latestAttemptWorkerId"));

            Object outputObject = terminal.messages().get(0).get("output");
            assertInstanceOf(Map.class, outputObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) outputObject;
            assertEquals("cross-language-node", output.get("integrationProbe"));

            Object workerProfileObject = output.get("workerProfile");
            assertInstanceOf(Map.class, workerProfileObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> workerProfile = (Map<String, Object>) workerProfileObject;
            assertEquals("node-websocket-worker", workerProfile.get("runtime"));
            assertEquals(WORKER_ID, workerProfile.get("workerId"));
        }
    }

    private void waitForWorkerStatus(String workerId,
                                     String expectedStatus,
                                     ExternalNodeWorkerProcess workerProcess) throws InterruptedException {
        Worker latestWorker = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            workerProcess.assertAlive("External Node worker exited before reaching status " + expectedStatus);
            latestWorker = app.getAllWorkers().stream()
                    .filter(worker -> workerId.equals(worker.getWorkerId()))
                    .findFirst()
                    .orElse(null);
            if (latestWorker != null
                    && latestWorker.getStatus() != null
                    && expectedStatus.equals(latestWorker.getStatus().name())) {
                return;
            }
            Thread.sleep(250L);
        }

        workerProcess.assertAlive("External Node worker exited while waiting for worker status");
        assertNotNull(latestWorker, "Worker should have been registered in runtime");
        throw new AssertionError("Worker " + workerId + " did not reach status " + expectedStatus
                + ". Last runtime worker=" + latestWorker
                + "\nNode worker output:\n" + workerProcess.capturedOutput());
    }
}
