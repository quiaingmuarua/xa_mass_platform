package com.xa.mass.mock.e2e.support;

import com.xa.mass.base.debug.WorkerDebugMessageStore;
import com.xa.mass.mock.MockApplicationSpringBootApp;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Predicate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=true",
                "mock.client.workers-config=mock/test_mock_workers.json",
                "mass.mock.data.workers=mock/test_mock_workers.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts.json",
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
class WorkerManualDebugChatIntegrationTest extends AbstractMockE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "it-worker-0";

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketPropertiesWithClientUri(registry, WEBSOCKET_PORT);
    }

    @BeforeEach
    void setUp() {
        WorkerDebugMessageStore.clearAll();
    }

    @AfterEach
    void tearDown() {
        WorkerDebugMessageStore.clearAll();
    }

    @Test
    void sendsManualDebugChatAndRecordsDeliveryHistory() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workerId", WORKER_ID);
        request.put("project", "demoApp");
        request.put("msgType", "CONTROL");
        request.put("subMsgType", "manual-chat");
        request.put("payload", Map.of(
                "text", "protocol verify",
                "source", "integration_test"
        ));

        Map<String, Object> sendResponse = waitForSuccessfulSend(request);
        assertEquals(Boolean.TRUE, sendResponse.get("success"));
        assertEquals(WORKER_ID, sendResponse.get("workerId"));
        assertEquals("CONTROL", sendResponse.get("msgType"));
        assertEquals("manual-chat", sendResponse.get("subMsgType"));

        String messageId = String.valueOf(sendResponse.get("messageId"));
        assertFalse(messageId.isBlank());

        Map<String, Object> historyResponse = waitForHistory(
                WORKER_ID,
                items -> findOutbound(items, messageId) != null
                        && "DELIVERED".equals(String.valueOf(findOutbound(items, messageId).get("status")))
                        && findInboundReply(items, messageId) != null
        );

        List<Map<String, Object>> items = historyItems(historyResponse);
        Map<String, Object> outbound = findOutbound(items, messageId);
        Map<String, Object> inbound = findInboundReply(items, messageId);

        assertNotNull(outbound);
        assertEquals("OUTBOUND", outbound.get("direction"));
        assertEquals("CONTROL", outbound.get("msgType"));
        assertEquals("manual-chat", outbound.get("subMsgType"));
        assertEquals("DELIVERED", outbound.get("status"));
        assertTrue(String.valueOf(outbound.get("payloadJson")).contains("\"debug_chat\""));
        assertTrue(String.valueOf(outbound.get("payloadJson")).contains("\"protocol verify\""));

        assertNotNull(inbound);
        assertEquals("INBOUND", inbound.get("direction"));
        assertEquals("EVENT", inbound.get("msgType"));
        assertEquals("manual-chat", inbound.get("subMsgType"));
        assertEquals("RECEIVED", inbound.get("status"));
        assertEquals(messageId, inbound.get("replyToMessageId"));
        assertTrue(String.valueOf(inbound.get("payloadJson")).contains("\"debug_chat_ack\""));
        assertTrue(String.valueOf(inbound.get("payloadJson")).contains("\"protocol verify\""));
    }

    private Map<String, Object> waitForSuccessfulSend(Map<String, Object> request) throws InterruptedException {
        Map<String, Object> latest = null;
        for (int i = 0; i < 40; i++) {
            latest = exchange("/status/workers/send-message", HttpMethod.POST, request);
            if (Boolean.TRUE.equals(latest.get("success"))) {
                return latest;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Manual debug message was not accepted in time. Last response=" + latest);
    }

    private Map<String, Object> waitForHistory(String workerId, Predicate<List<Map<String, Object>>> condition)
            throws InterruptedException {
        Map<String, Object> latest = null;
        for (int i = 0; i < 40; i++) {
            latest = exchange("/status/workers/message-history?workerId=" + workerId, HttpMethod.GET, null);
            List<Map<String, Object>> items = historyItems(latest);
            if (condition.test(items)) {
                return latest;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Manual debug history did not reach expected state. Last response=" + latest);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyItems(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("items");
    }

    private Map<String, Object> findOutbound(List<Map<String, Object>> items, String messageId) {
        return items.stream()
                .filter(item -> "OUTBOUND".equals(item.get("direction")))
                .filter(item -> messageId.equals(String.valueOf(item.get("messageId"))))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> findInboundReply(List<Map<String, Object>> items, String replyToMessageId) {
        return items.stream()
                .filter(item -> "INBOUND".equals(item.get("direction")))
                .filter(item -> replyToMessageId.equals(String.valueOf(item.get("replyToMessageId"))))
                .findFirst()
                .orElse(null);
    }
}
