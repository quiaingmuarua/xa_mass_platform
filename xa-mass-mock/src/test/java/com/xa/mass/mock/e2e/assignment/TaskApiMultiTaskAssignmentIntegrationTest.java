package com.xa.mass.mock.e2e.assignment;

import com.xa.mass.mock.MockApplicationSpringBootApp;
import com.xa.mass.mock.e2e.support.AbstractMockE2eTest;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=true",
                "mock.client.devices-config=mock/test_mock_devices.json",
                "mass.mock.data.devices=mock/test_mock_devices.json",
                "mass.mock.data.tokens=mock/test_mock_tokens.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mock.client.retry-attempts=1",
                "mock.client.retry-delay=1",
                "mock.client.connection-timeout=5",
                "mock.client.ping-delay=60",
                "mock.client.ping-interval=60"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class TaskApiMultiTaskAssignmentIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @Test
    void twoReadyTasksAreAssignedAcrossSeparateDevicesAndBothComplete() throws Exception {
        String firstTaskId = createTaskId("multi-task-a", "multi task assignment integration", "target-a");
        String secondTaskId = createTaskId("multi-task-b", "multi task assignment integration", "target-b");

        assertEquals(Boolean.TRUE, audit(firstTaskId).get("success"));
        assertEquals(Boolean.TRUE, audit(secondTaskId).get("success"));

        TaskSnapshot first = waitForTerminalTask(firstTaskId);
        TaskSnapshot second = waitForTerminalTask(secondTaskId);

        assertTerminalSingleDeviceTask(first);
        assertTerminalSingleDeviceTask(second);

        String firstDeviceId = String.valueOf(first.messages().get(0).get("deviceId"));
        String secondDeviceId = String.valueOf(second.messages().get(0).get("deviceId"));
        assertNotNull(firstDeviceId);
        assertNotNull(secondDeviceId);
        assertEquals(2, Set.of(firstDeviceId, secondDeviceId).size());
    }

    private void assertTerminalSingleDeviceTask(TaskSnapshot snapshot) {
        assertEquals("TERMINAL", snapshot.task().get("status"));
        assertEquals("ALL_MESSAGES_SUCCEEDED", snapshot.task().get("terminalReason"));
        assertEquals(1, ((Number) snapshot.task().get("scheduleDeviceCnt")).intValue());
        assertEquals(1, ((Number) snapshot.task().get("taskSuccessNumber")).intValue());
        assertEquals(1, snapshot.messages().size());
        Map<String, Object> message = snapshot.messages().get(0);
        assertEquals("SUCCESS", message.get("status"));
        assertNotNull(message.get("deviceId"));
        assertNotNull(message.get("tokenId"));
        assertNotNull(message.get("batchId"));
    }

    private Map<String, Object> audit(String taskId) {
        return exchange("/status/api/tasks/" + taskId + "/audit?approved=true&comment=multi-task",
                HttpMethod.POST, null);
    }
}
