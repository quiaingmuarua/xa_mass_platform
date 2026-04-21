package com.xa.mass.mock.e2e.support;

import com.google.gson.Gson;
import com.xa.mass.base.debug.ManualDebugChatProtocol;
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
class WorkerManualDebugDisconnectIntegrationTest extends AbstractMockE2eTest {

    private static final Gson GSON = new Gson();
    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "it-worker-1";

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
    void disconnectCommandDisconnectsWorkerAfterAck() throws Exception {
        String messageId = sendManualCommand(Map.of(
                "event", "mock.disconnect"
        ));

        Map<String, Object> ack = waitForInboundReply(messageId);
        Map<String, Object> payload = parsePayload(ack);

        assertEquals(Boolean.TRUE, payload.get("commandExecuted"));
        assertEquals("mock.disconnect", payload.get("commandEvent"));

        Map<String, Object> commandResult = map(payload.get("commandResult"));
        assertEquals("ok", commandResult.get("status"));
        Map<String, Object> resultData = map(commandResult.get("data"));
        assertEquals(Boolean.TRUE, resultData.get("disconnectAfterAck"));
        assertEquals(WORKER_ID, resultData.get("disconnectWorkerId"));

        Map<String, Object> offlineResponse = waitForOffline();
        assertEquals(Boolean.FALSE, offlineResponse.get("success"));
        assertTrue(String.valueOf(offlineResponse.get("msg")).contains("offline"));
    }

    private String sendManualCommand(Map<String, Object> payload) throws InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workerId", WORKER_ID);
        request.put("project", "demoApp");
        request.put("msgType", "CONTROL");
        request.put("subMsgType", ManualDebugChatProtocol.SUB_MSG_TYPE);
        request.put("payload", payload);

        Map<String, Object> sendResponse = waitForSuccessfulSend(request);
        assertEquals(Boolean.TRUE, sendResponse.get("success"));
        String messageId = String.valueOf(sendResponse.get("messageId"));
        assertFalse(messageId.isBlank());
        return messageId;
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
        throw new AssertionError("Disconnect command was not accepted in time. Last response=" + latest);
    }

    private Map<String, Object> waitForInboundReply(String replyToMessageId) throws InterruptedException {
        Map<String, Object> latest = null;
        for (int i = 0; i < 40; i++) {
            latest = exchange("/status/workers/message-history?workerId=" + WORKER_ID, HttpMethod.GET, null);
            List<Map<String, Object>> items = historyItems(latest);
            Map<String, Object> inbound = findInboundReply(items, replyToMessageId);
            if (inbound != null) {
                return inbound;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Disconnect command reply did not arrive in time. Last response=" + latest);
    }

    private Map<String, Object> waitForOffline() throws InterruptedException {
        Map<String, Object> latest = null;
        Map<String, Object> probeRequest = new LinkedHashMap<>();
        probeRequest.put("workerId", WORKER_ID);
        probeRequest.put("project", "demoApp");
        probeRequest.put("msgType", "CONTROL");
        probeRequest.put("subMsgType", ManualDebugChatProtocol.SUB_MSG_TYPE);
        probeRequest.put("payload", Map.of("text", "post-disconnect probe"));

        for (int i = 0; i < 40; i++) {
            latest = exchange("/status/workers/send-message", HttpMethod.POST, probeRequest);
            if (Boolean.FALSE.equals(latest.get("success"))
                    && String.valueOf(latest.get("msg")).contains("offline")) {
                return latest;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Worker did not become offline after disconnect. Last response=" + latest);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyItems(Map<String, Object> response) {
        return (List<Map<String, Object>>) response.get("items");
    }

    private Map<String, Object> findInboundReply(List<Map<String, Object>> items, String replyToMessageId) {
        return items.stream()
                .filter(item -> "INBOUND".equals(item.get("direction")))
                .filter(item -> replyToMessageId.equals(String.valueOf(item.get("replyToMessageId"))))
                .findFirst()
                .orElse(null);
    }

    private Map<String, Object> parsePayload(Map<String, Object> historyItem) {
        assertNotNull(historyItem);
        Object payloadJson = historyItem.get("payloadJson");
        assertNotNull(payloadJson);
        return map(GSON.fromJson(String.valueOf(payloadJson), Map.class));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> map(Object value) {
        return (Map<String, Object>) value;
    }
}
