package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.ProjectionSampleE2eTest;
import com.xa.mass.server.e2e.support.ExternalJavaWorkerProcess;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.transport.socket.server.SocketTransportServer;
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
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.worker-contexts=mock/test_mock_worker_contexts_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.socket.enabled=true"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class JavaSocketWorkerBlackBoxIntegrationTest extends ProjectionSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String SOCKET_WORKER_ID = "java-worker-socket-001";
    private static final String SOCKET_WORKER_KEY = "java-worker-socket-key";
    private static final String WEBSOCKET_WORKER_ID = "java-worker-websocket-002";
    private static final String WEBSOCKET_WORKER_KEY = "java-worker-websocket-key";

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("mass.socket.port", () -> 0);
    }

    @Test
    void externalJavaSocketWorkerCompletesTaskThroughExplicitSocketAdapterRegistration() throws Exception {
        registerExternalWorkerSubmitter(SOCKET_WORKER_ID, SOCKET_WORKER_KEY, List.of("crawler.fetch-page"));

        HttpHeaders workerHeaders = credentialHeaders(SOCKET_WORKER_KEY);
        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", SOCKET_WORKER_ID,
                "adapterId", "socket",
                "transportHint", "realtime",
                "attributes", Map.of("lang", "java", "runtime", "java-socket-worker"),
                "eventBindings", List.of(Map.of(
                        "eventCode", "crawler.fetch-page",
                        "projectCodes", List.of("crawlerApp")
                ))
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertEquals("socket", responseData(registerResponse).get("adapterId"));
        assertEquals("realtime", responseData(registerResponse).get("transportHint"));
        assertFalse(app.isWorkerOnline(SOCKET_WORKER_ID), "control-plane registration must not create socket transport presence");

        Map<String, Object> createResponse = exchange("/api/v1/tasks", HttpMethod.POST, Map.of(
                "project", "crawlerApp",
                "userId", "crawler-agent",
                "sourceRef", "cross-language-java-socket-worker",
                "executionSpec", Map.of("batchSize", 1)
        ));
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "crawler.fetch-page", List.of(Map.of("url", "https://example.test/socket-java"))));
        assertApiOk(sealTask(taskId));

        assertApiOk(approveTask(taskId));

        RuntimeTaskSnapshot readyWhileOffline = waitForRuntimeTaskSnapshot(taskId, "READY", 10, 200L);
        assertEquals("READY", readyWhileOffline.task().get("status"));
        assertEquals(1, readyWhileOffline.stats().readyCount());
        assertEquals(0, readyWhileOffline.stats().inflightCount());
        assertTrue(readyWhileOffline.activeLeases().isEmpty());

        try (ExternalJavaWorkerProcess worker = ExternalJavaWorkerProcess.startSocketSample(
                SOCKET_WORKER_ID,
                "127.0.0.1",
                waitForPositiveIntSystemProperty(
                        SocketTransportServer.BOUND_PORT_PROPERTY,
                        "Socket server did not publish a bound port",
                        20,
                        100L
                ))) {
            waitForWorkerPresenceOnline(
                    SOCKET_WORKER_ID,
                    20,
                    250L,
                    () -> worker.assertAlive("External Java worker exited before reaching status ONLINE"),
                    worker::capturedOutput
            );
            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
            assertEquals(1, terminal.stats().finalCount());
            assertTrue(terminal.activeLeases().isEmpty());

            TaskSnapshot terminalView = fetchTaskSnapshot(taskId);
            assertEquals(SOCKET_WORKER_ID, terminalView.messages().get(0).get("latestAttemptWorkerId"));
            Object outputObject = terminalView.messages().get(0).get("output");
            assertInstanceOf(Map.class, outputObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) outputObject;
            assertEquals("cross-language-java-socket", output.get("integrationProbe"));
            assertEquals("crawler.fetch-page", output.get("eventCode"));
            assertEquals("https://example.test/socket-java", output.get("url"));

            @SuppressWarnings("unchecked")
            Map<String, Object> workerProfile = (Map<String, Object>) output.get("workerProfile");
            assertEquals("java-socket-worker", workerProfile.get("runtime"));
            assertEquals(SOCKET_WORKER_ID, workerProfile.get("workerId"));
        }
        waitForWorkerOffline(SOCKET_WORKER_ID, "socket worker should go offline after disconnect");
    }

    @Test
    void websocketAndSocketJavaSamplesCanCoexistWithoutCrossRouting() throws Exception {
        registerExternalWorkerSubmitter(WEBSOCKET_WORKER_ID, WEBSOCKET_WORKER_KEY, List.of("demo.dispatch"));
        registerExternalWorkerSubmitter(SOCKET_WORKER_ID, SOCKET_WORKER_KEY, List.of("crawler.fetch-page"));

        assertApiOk(exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", WEBSOCKET_WORKER_ID,
                "adapterId", "websocket",
                "transportHint", "realtime",
                "attributes", Map.of("lang", "java", "runtime", "java-websocket-worker"),
                "eventBindings", List.of(Map.of(
                        "eventCode", "demo.dispatch",
                        "projectCodes", List.of("demoApp")
                ))
        ), credentialHeaders(WEBSOCKET_WORKER_KEY)));

        assertApiOk(exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", SOCKET_WORKER_ID,
                "adapterId", "socket",
                "transportHint", "realtime",
                "attributes", Map.of("lang", "java", "runtime", "java-socket-worker"),
                "eventBindings", List.of(Map.of(
                        "eventCode", "crawler.fetch-page",
                        "projectCodes", List.of("crawlerApp")
                ))
        ), credentialHeaders(SOCKET_WORKER_KEY)));

        try (ExternalJavaWorkerProcess websocketWorker = ExternalJavaWorkerProcess.startWebSocketSample(
                WEBSOCKET_WORKER_ID,
                URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws"));
             ExternalJavaWorkerProcess socketWorker = ExternalJavaWorkerProcess.startSocketSample(
                     SOCKET_WORKER_ID,
                     "127.0.0.1",
                     waitForPositiveIntSystemProperty(
                             SocketTransportServer.BOUND_PORT_PROPERTY,
                             "Socket server did not publish a bound port",
                             20,
                             100L
                     ))) {
            waitForWorkerPresenceOnline(
                    WEBSOCKET_WORKER_ID,
                    20,
                    250L,
                    () -> websocketWorker.assertAlive("External Java worker exited before reaching status ONLINE"),
                    websocketWorker::capturedOutput
            );
            waitForWorkerPresenceOnline(
                    SOCKET_WORKER_ID,
                    20,
                    250L,
                    () -> socketWorker.assertAlive("External Java worker exited before reaching status ONLINE"),
                    socketWorker::capturedOutput
            );

            String websocketTaskId = createAndApproveTask("demoApp", "demo.dispatch", Map.of("target", "socket-coexist-java-ws"));
            String socketTaskId = createAndApproveTask("crawlerApp", "crawler.fetch-page", Map.of("url", "https://example.test/socket-coexist-java"));

            RuntimeTaskSnapshot websocketRuntimeTerminal = waitForTerminalRuntimeTask(websocketTaskId);
            RuntimeTaskSnapshot socketRuntimeTerminal = waitForTerminalRuntimeTask(socketTaskId);
            assertEquals("ALL_MESSAGES_SUCCEEDED", websocketRuntimeTerminal.task().get("terminalReason"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", socketRuntimeTerminal.task().get("terminalReason"));
            assertEquals(1, websocketRuntimeTerminal.stats().successCount());
            assertEquals(1, socketRuntimeTerminal.stats().successCount());
            assertTrue(websocketRuntimeTerminal.activeLeases().isEmpty());
            assertTrue(socketRuntimeTerminal.activeLeases().isEmpty());

            TaskSnapshot websocketTerminal = fetchTaskSnapshot(websocketTaskId);
            TaskSnapshot socketTerminal = fetchTaskSnapshot(socketTaskId);

            assertEquals(WEBSOCKET_WORKER_ID, websocketTerminal.messages().get(0).get("latestAttemptWorkerId"));
            assertEquals(SOCKET_WORKER_ID, socketTerminal.messages().get(0).get("latestAttemptWorkerId"));

            @SuppressWarnings("unchecked")
            Map<String, Object> websocketOutput = (Map<String, Object>) websocketTerminal.messages().get(0).get("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> socketOutput = (Map<String, Object>) socketTerminal.messages().get(0).get("output");
            @SuppressWarnings("unchecked")
            Map<String, Object> websocketProfile = (Map<String, Object>) websocketOutput.get("workerProfile");
            @SuppressWarnings("unchecked")
            Map<String, Object> socketProfile = (Map<String, Object>) socketOutput.get("workerProfile");

            assertEquals("java-websocket-worker", websocketProfile.get("runtime"));
            assertEquals("java-socket-worker", socketProfile.get("runtime"));
        }
    }

    private void registerExternalWorkerSubmitter(String workerId, String credential, List<String> eventCodes) {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId(workerId + "-principal")
                .credential(credential)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("demoApp", "crawlerApp"))
                .eventScopes(eventCodes)
                .attributes(Map.of("workerId", workerId))
                .build());
    }

    private String createAndApproveTask(String project, String eventCode, Map<String, Object> input) {
        Map<String, Object> createResponse = exchange("/api/v1/tasks", HttpMethod.POST, Map.of(
                "project", project,
                "userId", "integration-agent",
                "sourceRef", "task-" + eventCode,
                "executionSpec", Map.of("batchSize", 1)
        ));
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, eventCode, List.of(input)));
        assertApiOk(sealTask(taskId));
        assertApiOk(approveTask(taskId));
        return taskId;
    }

    private HttpHeaders credentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }
}
