package com.xa.mass.mock.e2e.lifecycle;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies the PAUSED 鈫?READY 鈫?RUNNING 鈫?TERMINAL path via a real resume call
 * followed by device connection and mock callback.
 *
 * <p>Specifically:
 * <ol>
 *   <li>Task is approved while NO devices are available 鈫?stays READY, scheduleDeviceCnt=0.</li>
 *   <li>Task is paused (READY 鈫?PAUSED).</li>
 *   <li>A matching device is registered and a mock client connects.</li>
 *   <li>Task is resumed (PAUSED 鈫?READY); {@code notifyTaskReady} kicks the assign worker.</li>
 *   <li>TaskAssignWorker assigns the task to the new device (READY 鈫?RUNNING).</li>
 *   <li>Mock client auto-sends a SUCCESS callback 鈫?task closes to TERMINAL.</li>
 * </ol>
 *
 * <p>This path is distinct from {@link TaskApiPauseCompletionIntegrationTest}, which covers
 * callbacks arriving <em>while paused</em> (no resume needed).
 */
@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=false",
                "mass.mock.data.devices=mock/test_mock_devices_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiResumeAndCompleteIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private DeviceManager deviceManager;

    @Test
    void resumedPausedTaskCompletesAfterDeviceConnectsAndSendsCallback() throws Exception {
        // 1. Create and approve a task 鈥?no devices online yet.
        String taskId = createTaskId("resume-and-complete", "resume and complete integration test", "target-a");

        Map<String, Object> approveResponse = exchange(
                "/status/api/tasks/" + taskId + "/audit?approved=true&comment=resume-and-complete",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, approveResponse.get("success"));

        // 2. Task reaches READY but stays there (no matching device).
        TaskSnapshot readySnapshot = waitForTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals(1, readySnapshot.messages().size());
        assertEquals("INIT", readySnapshot.messages().get(0).get("status"));

        // 3. Pause the READY task.
        Map<String, Object> pauseResponse = exchange(
                "/status/api/tasks/" + taskId + "/pause",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, pauseResponse.get("success"));

        TaskSnapshot pausedSnapshot = waitForTaskSnapshot(taskId, "PAUSED", 4, 500L);
        assertEquals("PAUSED", pausedSnapshot.task().get("status"));
        // Message is still INIT 鈥?no device was ever assigned.
        assertEquals("INIT", pausedSnapshot.messages().get(0).get("status"));

        // 4. Register a matching device and connect a mock client.
        String deviceId = "resume-device-0";
        registerDevice(deviceId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, deviceId);
        try {
            assertTrue(client.connectBlocking(), "Mock client failed to connect");

            // 5. Resume the task: PAUSED 鈫?READY 鈫?assign worker picks it up 鈫?RUNNING 鈫?TERMINAL.
            Map<String, Object> resumeResponse = exchange(
                    "/status/api/tasks/" + taskId + "/resume",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, resumeResponse.get("success"));

            // 6. Task should proceed all the way to TERMINAL via the new device.
            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("TERMINAL", terminalSnapshot.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());

            assertEquals(1, terminalSnapshot.messages().size());
            Map<String, Object> msg = terminalSnapshot.messages().get(0);
            assertEquals("SUCCESS", msg.get("status"));
            assertEquals(deviceId, msg.get("deviceId"));
            assertNotNull(msg.get("tokenId"));
            assertNotNull(msg.get("batchId"));
        } finally {
            client.disconnect();
        }
    }

    // 鈹€鈹€鈹€ helpers 鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€鈹€

    private void registerDevice(String deviceId) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setDeviceGroupId("us");
        device.setStatus(DeviceStatus.ONLINE);
        device.setSupportedProjects(List.of(Project.DEMO_APP));
        deviceManager.addDevice(device);

        Token token = new Token();
        token.setTokenId("token-" + deviceId);
        token.setDeviceId(deviceId);
        token.setChannel("us");
        token.setStatus(TokenStatus.LOGIN_READY);
        deviceManager.addToken(deviceId, token);
    }

}
