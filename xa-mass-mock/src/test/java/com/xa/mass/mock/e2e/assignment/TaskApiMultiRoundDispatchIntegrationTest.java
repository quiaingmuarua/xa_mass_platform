package com.xa.mass.mock.e2e.assignment;

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
class TaskApiMultiRoundDispatchIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private DeviceManager deviceManager;

    @Test
    void singleDeviceWithBatchSizeOneCompletesTaskAcrossMultipleRounds() throws Exception {
        String deviceId = "round-device-0";
        registerDevice(deviceId);

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl client = new MassWebSocketClientImpl(wsUri, deviceId);
        try {
            assertTrue(client.connectBlocking(), "Mock client failed to connect");

            String taskId = createTaskId(
                    "multi-round",
                    "single device multi round dispatch",
                    List.of("target-a", "target-b", "target-c"),
                    1
            );

            Map<String, Object> approveResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=multi-round",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, approveResponse.get("success"));

            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, ((Number) terminal.task().get("scheduleDeviceCnt")).intValue());
            assertEquals(3, ((Number) terminal.task().get("taskSuccessNumber")).intValue());
            assertEquals(3, terminal.messages().size());

            for (Map<String, Object> message : terminal.messages()) {
                assertEquals("SUCCESS", message.get("status"));
                assertEquals(deviceId, message.get("deviceId"));
            }

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
        device.setSupportedProjects(List.of("demoApp"));
        deviceManager.addDevice(device);

        Token token = new Token();
        token.setTokenId("token-" + deviceId);
        token.setDeviceId(deviceId);
        token.setChannel("us");
        token.setStatus(TokenStatus.IDLE);
        deviceManager.addToken(deviceId, token);
    }
}
