package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
class TaskApiTerminateReuseIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private DeviceManager deviceManager;

    @Test
    void terminatedTaskReleasesSingleDeviceForNextTask() throws Exception {
        String deviceId = "terminate-reuse-device-0";
        registerDevice(deviceId);

        String firstTaskId = createTaskId("terminate-reuse-first", "terminate reuse first", "target-a");
        Map<String, Object> firstApprove = audit(firstTaskId, "terminate-reuse-1");
        assertEquals(Boolean.TRUE, firstApprove.get("success"));

        TaskSnapshot firstRunning = waitForTaskSnapshot(firstTaskId, "RUNNING", 20, 500L);
        assertEquals(deviceId, firstRunning.messages().get(0).get("deviceId"));

        Map<String, Object> firstTerminate = exchange(
                "/status/api/tasks/" + firstTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, firstTerminate.get("success"));

        TaskSnapshot firstTerminal = waitForTaskSnapshot(firstTaskId, "TERMINAL", 20, 500L);
        assertEquals("EXPIRED", firstTerminal.messages().get(0).get("status"));
        assertEquals(TokenStatus.LOGIN_READY, deviceManager.getToken(deviceId).getStatus());

        String secondTaskId = createTaskId("terminate-reuse-second", "terminate reuse second", "target-b");
        Map<String, Object> secondApprove = audit(secondTaskId, "terminate-reuse-2");
        assertEquals(Boolean.TRUE, secondApprove.get("success"));

        TaskSnapshot secondRunning = waitForTaskSnapshot(secondTaskId, "RUNNING", 20, 500L);
        assertEquals(deviceId, secondRunning.messages().get(0).get("deviceId"));

        Map<String, Object> secondTerminate = exchange(
                "/status/api/tasks/" + secondTaskId + "/terminate",
                HttpMethod.POST,
                null
        );
        assertEquals(Boolean.TRUE, secondTerminate.get("success"));
        waitForTaskSnapshot(secondTaskId, "TERMINAL", 20, 500L);
        assertEquals(TokenStatus.LOGIN_READY, deviceManager.getToken(deviceId).getStatus());
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
        token.setStatus(TokenStatus.LOGIN_READY);
        deviceManager.addToken(deviceId, token);
    }
}
