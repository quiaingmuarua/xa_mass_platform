package com.xa.mass.mock.e2e.assignment;

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
class TaskApiSingleDeviceReuseIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private DeviceManager deviceManager;

    @Test
    void singleDeviceCanBeReusedAfterPreviousTaskCompletes() throws Exception {
        String deviceId = "reuse-device-0";
        registerDevice(deviceId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, deviceId);
        try {
            assertTrue(client.connectBlocking(), "Mock client failed to connect");

            String firstTaskId = createTaskId("reuse-first", "single device reuse first", "target-a");
            Map<String, Object> firstApprove = exchange(
                    "/status/api/tasks/" + firstTaskId + "/audit?approved=true&comment=single-device-reuse-1",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, firstApprove.get("success"));
            TaskSnapshot firstTerminal = waitForTaskSnapshot(firstTaskId, "TERMINAL", 20, 500L);
            assertEquals(deviceId, firstTerminal.messages().get(0).get("deviceId"));

            String secondTaskId = createTaskId("reuse-second", "single device reuse second", "target-b");
            Map<String, Object> secondApprove = exchange(
                    "/status/api/tasks/" + secondTaskId + "/audit?approved=true&comment=single-device-reuse-2",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, secondApprove.get("success"));
            TaskSnapshot secondTerminal = waitForTaskSnapshot(secondTaskId, "TERMINAL", 20, 500L);
            assertEquals(deviceId, secondTerminal.messages().get(0).get("deviceId"));

            Token token = deviceManager.getToken(deviceId);
            assertEquals(TokenStatus.IDLE, token.getStatus());
        } finally {
            client.disconnect();
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
