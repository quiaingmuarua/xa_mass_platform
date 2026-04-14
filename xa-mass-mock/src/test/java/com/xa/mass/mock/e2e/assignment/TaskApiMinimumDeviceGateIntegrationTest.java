package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.client.MassWebSocketClientImpl;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
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
                "mass.mock.data.devices=mock/test_mock_devices_empty.json",
                "mass.mock.data.tokens=mock/test_mock_tokens_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiMinimumDeviceGateIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private DeviceManager deviceManager;

    @Autowired
    private TaskManager taskManager;

    @Test
    void readyTaskWaitsUntilMinimumDeviceCountIsSatisfied() throws Exception {
        String firstDeviceId = "min-gate-device-0";
        registerDevice(firstDeviceId);

        String taskId = createTaskId("min-device-gate", "minimum device gate integration", "target-a");
        Task task = taskManager.getTask(taskId);
        task.setRunTaskMinDeviceCnt(2);
        taskManager.updateTask(task);

        Map<String, Object> auditResponse = audit(taskId, "min-device-gate");
        assertEquals(Boolean.TRUE, auditResponse.get("success"));

        TaskSnapshot readySnapshot = waitForTaskSnapshot(taskId, "READY", 8, 500L);
        assertEquals(0, ((Number) readySnapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals("INIT", readySnapshot.messages().get(0).get("status"));
        assertEquals(TokenStatus.IDLE, deviceManager.getToken(firstDeviceId).getStatus());

        String secondDeviceId = "min-gate-device-1";
        registerDevice(secondDeviceId);

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl firstClient = new MassWebSocketClientImpl(uri, firstDeviceId);
        MassWebSocketClientImpl secondClient = new MassWebSocketClientImpl(uri, secondDeviceId);
        try {
            assertTrue(firstClient.connectBlocking(), "first mock client failed to connect");
            assertTrue(secondClient.connectBlocking(), "second mock client failed to connect");

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("scheduleDeviceCnt")).intValue());
            assertEquals(1, ((Number) terminalSnapshot.task().get("taskSuccessNumber")).intValue());
        } finally {
            firstClient.disconnect();
            secondClient.disconnect();
        }
    }

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
        token.setStatus(TokenStatus.IDLE);
        deviceManager.addToken(deviceId, token);
    }
}
