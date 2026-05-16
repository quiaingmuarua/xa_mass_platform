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
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
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
            Map<String, Object> firstApprove = approveTask(firstTaskId);
            assertApiOk(firstApprove);
            RuntimeTaskSnapshot firstTerminal = waitForRuntimeTaskSnapshot(firstTaskId, "TERMINAL", 20, 500L);
            assertEquals(1, firstTerminal.stats().successCount());

            String secondTaskId = createTaskId("reuse-second", "single worker reuse second", "target-b");
            Map<String, Object> secondApprove = approveTask(secondTaskId);
            assertApiOk(secondApprove);
            RuntimeTaskSnapshot secondTerminal = waitForRuntimeTaskSnapshot(secondTaskId, "TERMINAL", 20, 500L);
            assertEquals(1, secondTerminal.stats().successCount());

            assertEquals(0, secondTerminal.activeLeases().size());
        } finally {
            client.disconnect();
        }
    }

    private void registerWorker(String workerId) {
        registerSdkWorkerWithContext(workerId, "us");
    }
}

