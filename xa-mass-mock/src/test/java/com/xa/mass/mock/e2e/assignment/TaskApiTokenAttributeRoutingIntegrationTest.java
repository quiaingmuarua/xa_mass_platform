package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.base.enums.Project;
import com.xa.mass.base.enums.device.DeviceStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.rules.RuleDefinition;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.rules.RuleType;
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
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

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
class TaskApiTokenAttributeRoutingIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Autowired
    private DeviceManager deviceManager;

    @Autowired
    private RuleManager<Map<String, Object>> ruleManager;

    @Test
    void routesTaskUsingTokenAttributesCountryLabel() throws Exception {
        ruleManager.clear();
        ruleManager.addDefaultRules(List.of(
                rule("basic_device_check", "isDeviceAvailable == true && isDeviceLocked == false"),
                rule("token_status_check", "isTokenAllocatable == true && isTokenAvailable == true"),
                rule("app_support_check", "supportsProject == true"),
                rule("token_attribute_country", "tokenAttributes['country'] == taskCountry")
        ));

        addCandidate("matched-device", "us");
        addCandidate("other-device", "gb");

        URI uri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        MassWebSocketClientImpl matchedClient = new MassWebSocketClientImpl(uri, "matched-device");
        try {
            assertTrue(matchedClient.connectBlocking(), "matched device client failed to connect");

            String taskId = createTaskId("token-attribute-routing", "attribute routing integration", "target-a");
            Map<String, Object> auditResponse = exchange(
                    "/status/api/tasks/" + taskId + "/audit?approved=true&comment=token-attribute-routing",
                    HttpMethod.POST,
                    null
            );
            assertEquals(Boolean.TRUE, auditResponse.get("success"));

            TaskSnapshot terminalSnapshot = waitForTaskSnapshot(taskId, "TERMINAL", 20, 500L);
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminalSnapshot.task().get("terminalReason"));
            assertEquals(1, ((Number) terminalSnapshot.task().get("scheduleDeviceCnt")).intValue());
            assertEquals(1, terminalSnapshot.messages().size());

            Map<String, Object> message = terminalSnapshot.messages().get(0);
            assertEquals("matched-device", message.get("deviceId"));
            assertEquals("token-matched-device", message.get("tokenId"));
            assertEquals("SUCCESS", message.get("status"));
            assertNotNull(message.get("batchId"));
        } finally {
            matchedClient.disconnect();
        }
    }

    private RuleDefinition rule(String id, String content) {
        RuleDefinition rule = new RuleDefinition();
        rule.setId(id);
        rule.setType(RuleType.QL_EXPRESS);
        rule.setContent(content);
        return rule;
    }

    private void addCandidate(String deviceId, String countryAttribute) {
        Device device = new Device();
        device.setDeviceId(deviceId);
        device.setGroupId("us");
        device.setStatus(DeviceStatus.ONLINE);
        device.setSupportedProjects(List.of(Project.DEMO_APP));
        deviceManager.addDevice(device);

        Token token = new Token();
        token.setTokenId("token-" + deviceId);
        token.setDeviceId(deviceId);
        token.setChannel("us");
        token.setStatus(TokenStatus.LOGIN_READY);
        token.setAttributes(Map.of("country", countryAttribute));
        deviceManager.addToken(deviceId, token);
    }
}
