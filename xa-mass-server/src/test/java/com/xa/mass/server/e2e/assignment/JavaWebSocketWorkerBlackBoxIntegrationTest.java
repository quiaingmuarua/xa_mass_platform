package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractSampleE2eTest;
import com.xa.mass.server.e2e.support.ExternalJavaWorkerProcess;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.PrincipalContext;
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
                "mass.mock.data.rules=mock/test_mock_rules.json"
        }
)
@ActiveProfiles("dev")
@DirtiesContext
class JavaWebSocketWorkerBlackBoxIntegrationTest extends AbstractSampleE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final String WORKER_ID = "java-worker-realtime-001";
    private static final String WORKER_KEY = "java-worker-realtime-key";

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
    }

    @Test
    void externalJavaWorkerCompletesTaskThroughRealtimeRegistrationAndEventCodeRuntime() throws Exception {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId("java-realtime-worker")
                .credential(WORKER_KEY)
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", WORKER_ID))
                .build());

        HttpHeaders workerHeaders = sdkCredentialHeaders(WORKER_KEY);
        Map<String, Object> registerResponse = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", WORKER_ID,
                "adapterId", "websocket",
                "transportHint", "realtime",
                "attributes", Map.of("lang", "java", "runtime", "java-websocket-worker"),
                "eventBindings", List.of(Map.of(
                        "eventCode", "crawler.fetch-page",
                        "projectCodes", List.of("crawlerApp")
                ))
        ), workerHeaders);
        assertApiOk(registerResponse);
        assertEquals("websocket", responseData(registerResponse).get("adapterId"));
        assertEquals("realtime", responseData(registerResponse).get("transportHint"));
        assertFalse(app.isWorkerOnline(WORKER_ID), "control-plane registration must not mark realtime worker online");

        Map<String, Object> createResponse = exchange("/api/v1/tasks", HttpMethod.POST, Map.of(
                "project", "crawlerApp",
                "userId", "crawler-agent",
                "sourceRef", "cross-language-java-worker",
                "executionSpec", Map.of("batchSize", 1)
        ));
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "crawler.fetch-page", List.of(Map.of("url", "https://example.test/realtime-java")), 3));
        assertApiOk(sealTask(taskId));

        Map<String, Object> approveResponse = approveTask(taskId);
        assertApiOk(approveResponse);

        TaskSnapshot readyWhileOffline = waitForTaskSnapshot(taskId, "READY", 10, 200L);
        assertEquals("READY", readyWhileOffline.task().get("status"));
        assertEquals(1, readyWhileOffline.messages().size());
        assertEquals(null, readyWhileOffline.messages().get(0).get("latestAttemptWorkerId"));

        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        try (ExternalJavaWorkerProcess worker = ExternalJavaWorkerProcess.startWebSocketSample(WORKER_ID, wsUri)) {
            waitForWorkerStatus(
                    WORKER_ID,
                    "ONLINE",
                    20,
                    250L,
                    () -> worker.assertAlive("External Java worker exited before reaching status ONLINE"),
                    worker::capturedOutput
            );
            TaskSnapshot terminal = waitForTerminalTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(WORKER_ID, terminal.messages().get(0).get("latestAttemptWorkerId"));

            Object outputObject = terminal.messages().get(0).get("output");
            assertInstanceOf(Map.class, outputObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> output = (Map<String, Object>) outputObject;
            assertEquals("cross-language-java-websocket", output.get("integrationProbe"));
            assertEquals("crawler.fetch-page", output.get("eventCode"));
            assertEquals("https://example.test/realtime-java", output.get("url"));
            assertTrue(output.containsKey("fetchedAt"));

            Object workerProfileObject = output.get("workerProfile");
            assertInstanceOf(Map.class, workerProfileObject);
            @SuppressWarnings("unchecked")
            Map<String, Object> workerProfile = (Map<String, Object>) workerProfileObject;
            assertEquals("java-websocket-worker", workerProfile.get("runtime"));
            assertEquals(WORKER_ID, workerProfile.get("workerId"));
        }
        waitForWorkerOffline(WORKER_ID, "realtime java websocket worker should go offline after disconnect");
    }

    private HttpHeaders sdkCredentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }
}
