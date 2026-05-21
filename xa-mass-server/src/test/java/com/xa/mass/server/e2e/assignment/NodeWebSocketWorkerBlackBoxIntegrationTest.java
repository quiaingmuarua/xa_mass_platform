package com.xa.mass.server.e2e.assignment;

import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.server.e2e.support.ProjectionSampleE2eTest;
import com.xa.mass.server.e2e.support.ExternalNodeWorkerProcess;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.model.WorkerSnapshot;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Black-box proof that a non-JVM worker can participate through the public
 * control-plane registration plus WebSocket transport boundary.
 *
 * <p>The worker identity is established at WebSocket handshake time and
 * canonical task dispatches are executed by an eventCode-driven local runtime.
 */
@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class NodeWebSocketWorkerBlackBoxIntegrationTest extends ProjectionSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "node-worker-realtime-001";
    private static final String WORKER_KEY = "node-worker-realtime-key";
    private static final String ADAPTER_NODE_ID = "node-websocket-node";
    private static final String STOCK_WORKER_ID = "stock-ws-worker-001";
    private static final String STOCK_WORKER_KEY = "stock-ws-worker-key";
    private static final String STOCK_ADAPTER_NODE_ID = "stock-websocket-node";

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void externalNodeWorkerCompletesTaskThroughRealtimeRegistrationAndEventCodeRuntime() throws Exception {
        HttpHeaders workerHeaders = credentialHeaders(WORKER_KEY);
        declareExternalWorkerGroup("node-websocket-crawler", "crawlerApp", "crawler.fetch-page", workerHeaders);
        bindExternalAdapterNode(ADAPTER_NODE_ID, "node-websocket-crawler", workerHeaders);
        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", WORKER_ID,
                "adapterNodeId", ADAPTER_NODE_ID,
                "workerGroupId", "node-websocket-crawler",
                "adapterId", "websocket",
                "transportHint", "realtime",
                "attributes", Map.of(
                        "lang", "node",
                        "runtime", "node-websocket-worker",
                        "routingTags", "web,us",
                        "country", "us",
                        "region", "us"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        WorkerSnapshot registeredWorker = app.getWorker(WORKER_ID);
        assertNotNull(registeredWorker);
        assertEquals("websocket", responseData(registerResponse).get("adapterId"));
        assertEquals("realtime", responseData(registerResponse).get("transportHint"));
        assertFalse(app.isWorkerOnline(WORKER_ID), "control-plane registration must not create realtime transport presence");

        Map<String, Object> createResponse = exchange("/api/v1/tasks", HttpMethod.POST, Map.of(
                "project", "crawlerApp",
                "userId", "crawler-agent",
                "sourceRef", "cross-language-node-worker",
                "executionSpec", Map.of("batchSize", 1)
        ));
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "crawler.fetch-page", List.of(Map.of("url", "https://example.test/realtime-node"))));
        assertApiOk(sealTask(taskId));

        Map<String, Object> approveResponse = approveTask(taskId);
        assertApiOk(approveResponse);

        RuntimeTaskSnapshot readyWhileOffline = waitForRuntimeTaskSnapshot(taskId, "READY", 10, 200L);
        assertEquals("READY", readyWhileOffline.task().get("status"));
        assertEquals(1, readyWhileOffline.stats().readyCount());
        assertEquals(0, readyWhileOffline.stats().inflightCount());
        assertTrue(readyWhileOffline.activeLeases().isEmpty());

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        try (ExternalNodeWorkerProcess worker = ExternalNodeWorkerProcess.startWebSocketSample(WORKER_ID, wsUri)) {
            waitForWorkerPresenceOnline(
                    WORKER_ID,
                    20,
                    250L,
                    () -> worker.assertAlive("External Node worker exited before reaching status ONLINE"),
                    worker::capturedOutput
            );
            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
            assertEquals(1, terminal.stats().finalCount());
            assertTrue(terminal.activeLeases().isEmpty());

            TaskSnapshot terminalView = fetchTaskSnapshot(taskId);
            assertEquals(WORKER_ID, terminalView.messages().get(0).get("latestAttemptWorkerId"));
            Object outputObject = terminalView.messages().get(0).get("output");
            assertInstanceOf(Map.class, outputObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) outputObject;
            assertEquals("cross-language-node", output.get("integrationProbe"));
            assertEquals("crawler.fetch-page", output.get("eventCode"));
            assertEquals("https://example.test/realtime-node", output.get("url"));
            assertTrue(output.containsKey("fetchedAt"));

            Object workerProfileObject = output.get("workerProfile");
            assertInstanceOf(Map.class, workerProfileObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> workerProfile = (Map<String, Object>) workerProfileObject;
            assertEquals("node-websocket-worker", workerProfile.get("runtime"));
            assertEquals(WORKER_ID, workerProfile.get("workerId"));
        }
        waitForWorkerOffline(WORKER_ID, "realtime websocket worker should go offline after disconnect");
    }

    @Test
    @Tag("projection-residue")
    void externalNodeWebSocketStockWorkerHandlesAsyncRpcRequestIdsThroughStreamTask() throws Exception {
        HttpHeaders workerHeaders = credentialHeaders(STOCK_WORKER_KEY);
        declareExternalWorkerGroup("node-websocket-stock", "crawlerApp", "stock.quote.fetch", workerHeaders);
        bindExternalAdapterNode(STOCK_ADAPTER_NODE_ID, "node-websocket-stock", workerHeaders);
        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", STOCK_WORKER_ID,
                "adapterNodeId", STOCK_ADAPTER_NODE_ID,
                "workerGroupId", "node-websocket-stock",
                "adapterId", "websocket",
                "transportHint", "realtime",
                "attributes", Map.of(
                        "lang", "node",
                        "runtime", "node-websocket-worker",
                        "workerType", "stock-crawler",
                        "routingTags", "us,stock",
                        "country", "us",
                        "region", "us",
                        "market", "NASDAQ"
                )
        ), workerHeaders);
        assertApiOk(registerResponse);
        WorkerSnapshot registeredWorker = app.getWorker(STOCK_WORKER_ID);
        assertNotNull(registeredWorker);
        assertEquals("websocket", responseData(registerResponse).get("adapterId"));
        assertEquals("realtime", responseData(registerResponse).get("transportHint"));
        assertFalse(app.isWorkerOnline(STOCK_WORKER_ID), "control-plane registration must not create realtime transport presence");

        String sourceUrl = "http://127.0.0.1:" + port + "/api/v1/catalog/events/stock.quote.fetch";
        String initialRequestId = "stockreq-init-0001";
        Map<String, Object> createBody = new LinkedHashMap<>();
        createBody.put("project", "crawlerApp");
        createBody.put("userId", "stock-agent");
        createBody.put("sourceRef", "stock-quote-stream");
        createBody.put("sharedConfig", Map.of("routingCode", "us", "sourceUrl", sourceUrl));
        createBody.put("executionSpec", Map.of(
                "batchSize", 1,
                "workloadClass", "INTERACTIVE"
        ));
        Map<String, Object> createResponse = createTaskShell(createBody);
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "stock.quote.fetch", List.of(Map.of(
                "requestId", initialRequestId,
                "symbol", "AAPL",
                "market", "NASDAQ"
        ))));

        assertApiOk(approveTask(taskId));

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        try (ExternalNodeWorkerProcess worker = ExternalNodeWorkerProcess.startWebSocketSample(STOCK_WORKER_ID, wsUri)) {
            waitForWorkerPresenceOnline(
                    STOCK_WORKER_ID,
                    20,
                    250L,
                    () -> worker.assertAlive("External Node worker exited before reaching status ONLINE"),
                    worker::capturedOutput
            );

            String successRequestId = "stockreq-async-0002";
            assertApiOk(exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                    "eventCode", "stock.quote.fetch",
                    "items", List.of(Map.of(
                            "requestId", successRequestId,
                            "symbol", "MSFT",
                            "market", "NASDAQ",
                            "sourceUrl", sourceUrl
                    ))
            )));

            String invalidRequestId = "stockreq-invalid-0003";
            assertApiOk(exchange("/api/v1/tasks/" + taskId + "/items", HttpMethod.POST, Map.of(
                    "eventCode", "stock.quote.fetch",
                    "items", List.of(Map.of(
                            "requestId", invalidRequestId,
                            "market", "NASDAQ",
                            "sourceUrl", sourceUrl
                    ))
            )));

            TaskSnapshot stockResults = waitForTaskSnapshot(
                    taskId,
                    snapshot -> messageByRequestId(snapshot, successRequestId, "SUCCESS") != null
                            && messageByRequestId(snapshot, invalidRequestId, "FAILED") != null,
                    "stock stream messages by requestId should be final",
                    40,
                    250L
            );

            Map<String, Object> successMessage = messageByRequestId(stockResults, successRequestId, "SUCCESS");
            assertNotNull(successMessage);
            assertEquals(STOCK_WORKER_ID, successMessage.get("latestAttemptWorkerId"));
            @SuppressWarnings("unchecked")
            Map<String, Object> successOutput = (Map<String, Object>) successMessage.get("output");
            assertEquals(successRequestId, successOutput.get("requestId"));
            assertEquals("MSFT", successOutput.get("symbol"));
            assertEquals("NASDAQ", successOutput.get("market"));
            assertTrue(successOutput.get("price") instanceof Number);
            assertEquals("USD", successOutput.get("currency"));
            assertEquals(sourceUrl, successOutput.get("source"));
            assertTrue(successOutput.get("elapsedMs") instanceof Number);
            @SuppressWarnings("unchecked")
            Map<String, Object> workerProfile = (Map<String, Object>) successOutput.get("workerProfile");
            assertEquals("node-websocket-worker", workerProfile.get("runtime"));
            assertEquals(STOCK_WORKER_ID, workerProfile.get("workerId"));

            Map<String, Object> failedMessage = messageByRequestId(stockResults, invalidRequestId, "FAILED");
            assertNotNull(failedMessage);
            assertEquals("INVALID_INPUT", failedMessage.get("errorCode"));
            @SuppressWarnings("unchecked")
            Map<String, Object> failedOutput = (Map<String, Object>) failedMessage.get("output");
            assertEquals(invalidRequestId, failedOutput.get("requestId"));

            assertApiOk(sealTask(taskId));
            RuntimeTaskSnapshot sealedTerminal = waitForRuntimeTaskSnapshot(taskId, "TERMINAL", 30, 250L);
            assertEquals("TERMINAL", sealedTerminal.task().get("status"));
            assertTrue(List.of("MIXED_MESSAGE_RESULTS", "ALL_MESSAGES_FAILED", "ALL_MESSAGES_SUCCEEDED")
                    .contains(String.valueOf(sealedTerminal.task().get("terminalReason"))));
            assertEquals(3, sealedTerminal.stats().finalCount());
            assertTrue(sealedTerminal.activeLeases().isEmpty());
        }
        waitForWorkerOffline(STOCK_WORKER_ID, "stock websocket worker should go offline after disconnect");
    }

    private HttpHeaders credentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> messageByRequestId(TaskSnapshot snapshot, String requestId, String status) {
        for (Map<String, Object> message : snapshot.messages()) {
            Map<String, Object> input = (Map<String, Object>) message.get("input");
            Map<String, Object> output = (Map<String, Object>) message.get("output");
            boolean inputMatches = input != null && requestId.equals(input.get("requestId"));
            boolean outputMatches = output != null && requestId.equals(output.get("requestId"));
            if ((inputMatches || outputMatches) && status.equals(message.get("status"))) {
                return message;
            }
        }
        return null;
    }
}
