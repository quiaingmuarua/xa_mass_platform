package com.xa.mass.mock.e2e.support;

import com.google.gson.Gson;
import com.xa.mass.base.debug.WorkerControlEventProtocol;
import com.xa.mass.base.debug.WorkerControlMessageProtocol;
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

import static org.junit.jupiter.api.Assertions.*;

@SpringBootTest(
        classes = MockApplicationSpringBootApp.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "mock.client.auto-start=true",
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
class WorkerControlEventCommandIntegrationTest extends AbstractMockE2eTest {

    private static final Gson GSON = new Gson();
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
    void executesMockCommandAndReturnsStructuredAck() throws Exception {
        String delayMessageId = sendEventCommand(WORKER_ID, "mock.delay.response", Map.of(
                "millis", 400
        ));

        Map<String, Object> delayAck = waitForInboundReply(WORKER_ID, delayMessageId);
        Map<String, Object> delayPayload = parsePayload(delayAck);

        assertEquals(
                WorkerControlMessageProtocol.MESSAGE_KIND_ACK,
                delayPayload.get(WorkerControlMessageProtocol.MESSAGE_KIND_FIELD)
        );
        assertEquals(Boolean.TRUE, delayPayload.get("commandExecuted"));
        assertEquals("mock.delay.response", delayPayload.get("commandEvent"));

        Map<String, Object> delayCommandResult = map(delayPayload.get("commandResult"));
        assertEquals("ok", delayCommandResult.get("status"));
        Map<String, Object> delayResultData = map(delayCommandResult.get("data"));
        Map<String, Object> delayState = map(delayResultData.get("state"));
        assertEquals(400.0d, delayState.get("taskResponseDelayMillis"));

        String stateMessageId = sendEventCommand(WORKER_ID, "mock.state.get", Map.of());

        Map<String, Object> stateAck = waitForInboundReply(WORKER_ID, stateMessageId);
        Map<String, Object> statePayload = parsePayload(stateAck);
        assertEquals("mock.state.get", statePayload.get("commandEvent"));

        Map<String, Object> stateCommandResult = map(statePayload.get("commandResult"));
        Map<String, Object> stateResultData = map(stateCommandResult.get("data"));
        Map<String, Object> stateSnapshot = map(stateResultData.get("state"));
        assertEquals(400.0d, stateSnapshot.get("taskResponseDelayMillis"));
    }

    @Test
    void eventFirstEndpointExecutesMockCommandWithoutLegacyMessageShape() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workerId", WORKER_ID);
        request.put("project", "demoApp");
        request.put("event", "mock.state.get");
        request.put("requestId", "event-first-debug-1");
        request.put("principal", Map.of(
                "clientId", "debug-client",
                "userId", "debug-user"
        ));
        request.put("payload", Map.of());

        Map<String, Object> sendResponse = waitForSuccessfulEventSend(request);
        Map<String, Object> sendData = responseData(sendResponse);
        assertEquals(WORKER_ID, sendData.get("workerId"));
        assertEquals("mock.state.get", sendData.get("eventCode"));
        assertEquals("event-first-debug-1", sendData.get("requestId"));
        assertFalse(sendData.containsKey("msgType"));
        assertFalse(sendData.containsKey("subMsgType"));

        String messageId = String.valueOf(sendData.get("messageId"));
        Map<String, Object> stateAck = waitForInboundReply(WORKER_ID, messageId);
        Map<String, Object> statePayload = parsePayload(stateAck);
        assertEquals("mock.state.get", statePayload.get("commandEvent"));
        assertEquals(Boolean.TRUE, statePayload.get("commandExecuted"));
    }

    @Test
    void historyPreservesGlobalEventCodeAcrossTransportBridgeWhenProjectIsOmitted() throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workerId", WORKER_ID);
        request.put("event", "mock.state.get");
        request.put("requestId", "event-history-1");
        request.put("payload", Map.of());

        Map<String, Object> sendResponse = waitForSuccessfulEventSend(request);
        Map<String, Object> sendData = responseData(sendResponse);
        assertEquals(WORKER_ID, sendData.get("workerId"));
        assertEquals("demoApp", sendData.get("project"));
        assertEquals("mock.state.get", sendData.get("eventCode"));

        String messageId = String.valueOf(sendData.get("messageId"));
        assertFalse(messageId.isBlank());

        Map<String, Object> outbound = waitForHistoryItem(WORKER_ID,
                item -> "OUTBOUND".equals(item.get("direction"))
                        && messageId.equals(String.valueOf(item.get("messageId"))));
        Map<String, Object> inbound = waitForHistoryItem(WORKER_ID,
                item -> "INBOUND".equals(item.get("direction"))
                        && messageId.equals(String.valueOf(item.get("replyToMessageId"))));

        assertEquals("mock.state.get", outbound.get("eventCode"));
        assertEquals("CONTROL", outbound.get("msgType"));
        assertEquals("event", outbound.get("subMsgType"));

        assertEquals("mock.state.get", inbound.get("eventCode"));
        assertEquals("CONTROL", inbound.get("msgType"));
        assertEquals(WorkerControlEventProtocol.SUB_MSG_TYPE, inbound.get("subMsgType"));
    }

    private String sendEventCommand(String workerId, String event, Map<String, Object> payload) throws InterruptedException {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workerId", workerId);
        request.put("project", "demoApp");
        request.put("event", event);
        request.put("payload", payload);
        request.put("requestId", "integration-" + event.replace('.', '-'));

        Map<String, Object> sendResponse = waitForSuccessfulEventSend(request);
        Map<String, Object> sendData = responseData(sendResponse);
        assertEquals(workerId, sendData.get("workerId"));
        assertEquals(event, sendData.get("eventCode"));
        String messageId = String.valueOf(sendData.get("messageId"));
        assertFalse(messageId.isBlank());
        return messageId;
    }

    private Map<String, Object> waitForSuccessfulEventSend(Map<String, Object> request) throws InterruptedException {
        Map<String, Object> latest = null;
        for (int i = 0; i < 40; i++) {
            latest = exchange("/status/workers/send-event", HttpMethod.POST, request);
            if (isApiOk(latest)) {
                return latest;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Event-first debug command was not accepted in time. Last response=" + latest);
    }

    private Map<String, Object> waitForInboundReply(String workerId, String replyToMessageId) throws InterruptedException {
        return waitForHistoryItem(workerId,
                item -> "INBOUND".equals(item.get("direction"))
                        && replyToMessageId.equals(String.valueOf(item.get("replyToMessageId"))));
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> historyItems(Map<String, Object> response) {
        return (List<Map<String, Object>>) responseData(response).get("items");
    }

    private Map<String, Object> waitForHistoryItem(String workerId,
                                                   Predicate<Map<String, Object>> matcher) throws InterruptedException {
        Map<String, Object> latest = null;
        for (int i = 0; i < 40; i++) {
            latest = exchange("/status/workers/message-history?workerId=" + workerId, HttpMethod.GET, null);
            List<Map<String, Object>> items = historyItems(latest);
            Map<String, Object> matched = items.stream()
                    .filter(matcher)
                    .findFirst()
                    .orElse(null);
            if (matched != null) {
                return matched;
            }
            Thread.sleep(250L);
        }
        throw new AssertionError("Worker control history item did not arrive in time. Last response=" + latest);
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
