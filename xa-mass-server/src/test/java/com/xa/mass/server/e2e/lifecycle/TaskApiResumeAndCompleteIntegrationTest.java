package com.xa.mass.server.e2e.lifecycle;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.workerpack.sample.client.SampleWorkerWebSocketClient;
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
class TaskApiResumeAndCompleteIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void resumedPausedTaskCompletesAfterWorkerConnectsAndSendsCallback() throws Exception {
        String taskId = createTaskId("resume-and-complete", "resume and complete integration test", "target-a");

        Map<String, Object> approveResponse = approveTask(taskId);
        assertApiOk(approveResponse);

        RuntimeTaskSnapshot readySnapshot = waitForRuntimeTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("peakAssignedWorkerCount")).intValue());
        assertEquals(1, readySnapshot.stats().readyCount());

        Map<String, Object> pauseResponse = pauseTask(taskId);
        assertApiOk(pauseResponse);

        RuntimeTaskSnapshot pausedSnapshot = waitForRuntimeTaskSnapshot(taskId, "PAUSED", 4, 500L);
        assertEquals("PAUSED", pausedSnapshot.task().get("status"));
        assertEquals(1, pausedSnapshot.stats().readyCount());

        String workerId = "resume-worker-0";
        registerSdkWorkerWithContext(workerId, "us");

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        SampleWorkerWebSocketClient client = new SampleWorkerWebSocketClient(wsUri, workerId);
        try {
            assertClientConnects(client, "Sample client failed to connect");

            Map<String, Object> resumeResponse = resumeTask(taskId);
            assertApiOk(resumeResponse);

            RuntimeTaskSnapshot terminalSnapshot = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("peakAssignedWorkerCount")).intValue());
            assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
            assertEquals(1, terminalSnapshot.stats().totalCount());
            assertEquals(1, terminalSnapshot.stats().successCount());
        } finally {
            client.disconnect();
        }
    }
}
