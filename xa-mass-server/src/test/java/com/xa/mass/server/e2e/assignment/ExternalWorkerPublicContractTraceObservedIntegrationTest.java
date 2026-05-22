package com.xa.mass.server.e2e.assignment;

import com.xa.mass.api.internal.SdkCredentialAuthSupport;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.server.XaMassServerApplication;
import com.xa.mass.server.e2e.support.AbstractTraceObservedE2eTest;
import com.xa.mass.server.e2e.support.ExternalJavaWorkerProcess;
import com.xa.mass.server.e2e.support.ExternalNodeWorkerProcess;
import com.xa.mass.sdk.MassSdkApplication;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.trace.operator.TraceAnalyzeResponse;
import com.xa.mass.transport.socket.server.SocketTransportServer;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.net.URI;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

@SpringBootTest(
        classes = XaMassServerApplication.class,
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "sample.client.auto-start=false",
                "sample.worker.auto-start=false",
                "mass.mock.data.workers=mock/test_mock_workers_empty.json",
                "mass.mock.data.tasks=mock/test_mock_tasks.json",
                "mass.mock.data.rules=mock/test_mock_rules.json",
                "mass.trace.sink.enabled=true",
                "mass.trace.sink.queue-capacity=256",
                "mass.trace.sink.rotate-after-lines=1",
                "mass.trace.sink.overflow-policy=FALLBACK_SYNC",
                "mass.trace.sink.shutdown-drain-timeout-ms=1500",
                "mass.socket.enabled=true"
        }
)
@ActiveProfiles("dev")
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_EACH_TEST_METHOD)
class ExternalWorkerPublicContractTraceObservedIntegrationTest extends AbstractTraceObservedE2eTest {

    private static final int WEBSOCKET_PORT = findFreePort();
    private static final Path TRACE_OUTPUT_DIR = traceOutputDir("external-worker-public-contract-trace-observed");

    @Autowired
    private MassSdkApplication app;

    @DynamicPropertySource
    static void registerProperties(DynamicPropertyRegistry registry) {
        registerWebSocketProperties(registry, WEBSOCKET_PORT);
        registry.add("mass.socket.port", () -> 0);
        registerTraceOutputDir(registry, TRACE_OUTPUT_DIR);
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("cases")
    void externalWorkerPublicContractSuccessIsObservedThroughCanonicalTrace(ExternalWorkerCase spec) throws Exception {
        registerWorkerCredential(spec);
        if (spec.requiresPreRegistration()) {
            registerRealtimeWorker(spec);
            assertFalse(app.isWorkerOnline(spec.workerId()),
                    "control-plane registration must not create transport presence");
        }

        String taskId = createCrawlerTask(spec.sourceRef(), spec.workerGroupId());
        RuntimeTaskSnapshot readyWhileOffline = waitForRuntimeTaskSnapshot(taskId, "READY", 10, 200L);
        assertEquals(1, readyWhileOffline.stats().readyCount());
        assertEquals(0, readyWhileOffline.stats().inflightCount());
        assertTrue(readyWhileOffline.activeLeases().isEmpty());

        try (AutoCloseable process = startWorker(spec)) {
            waitForWorkerPresenceOnline(
                    spec.workerId(),
                    60,
                    250L,
                    () -> assertWorkerProcessAlive(process, spec),
                    () -> capturedOutput(process)
            );

            RuntimeTaskSnapshot terminal = waitForTerminalRuntimeTask(taskId);
            assertEquals("TERMINAL", terminal.task().get("status"));
            assertEquals("ALL_MESSAGES_SUCCEEDED", terminal.task().get("terminalReason"));
            assertEquals(1, terminal.stats().successCount());
            assertEquals(1, terminal.stats().finalCount());
            assertTrue(terminal.activeLeases().isEmpty());

            TraceAnalyzeResponse trace = awaitTraceScenarioOk(
                    TRACE_OUTPUT_DIR,
                    "external-worker-public-contract-success",
                    taskId + "," + spec.workerId()
            );
            assertTrue(trace.eventTypeCounts().containsKey("TASK_TERMINAL_CLOSED"));
            assertTrue(trace.eventTypeCounts().containsKey("CALLBACK_ACCEPTED"));
        }
        waitForWorkerOffline(spec.workerId(), spec.workerId() + " should go offline after shutdown");
    }

    private String createCrawlerTask(String sourceRef, String workerGroupId) {
        Map<String, Object> createResponse = exchange("/api/v1/tasks", HttpMethod.POST, Map.of(
                "project", "crawlerApp",
                "userId", "crawler-agent",
                "sourceRef", sourceRef,
                "executionSpec", Map.of("batchSize", 1),
                "sharedConfig", Map.of(TaskSharedConfig.WORKER_GROUP_ID, workerGroupId)
        ));
        assertApiOk(createResponse);
        String taskId = String.valueOf(responseData(createResponse).get("taskId"));
        assertApiOk(appendTaskItems(taskId, "crawler.fetch-page", List.of(Map.of(
                "url", "http://127.0.0.1:" + port + "/api/v1/catalog/events/crawler.fetch-page"
        ))));
        assertApiOk(sealTask(taskId));
        assertApiOk(approveTask(taskId));
        return taskId;
    }

    private void registerWorkerCredential(ExternalWorkerCase spec) {
        app.registerSubmitter(SubmitterRegistration.builder()
                .principalId(spec.workerId() + "-principal")
                .credential(spec.workerKey())
                .permissions(List.of(PrincipalContext.EXTERNAL_WORKER_PERMISSION))
                .projectScopes(List.of("crawlerApp"))
                .eventScopes(List.of("crawler.fetch-page"))
                .attributes(Map.of("workerId", spec.workerId()))
                .build());
    }

    private void registerRealtimeWorker(ExternalWorkerCase spec) {
        HttpHeaders workerHeaders = credentialHeaders(spec.workerKey());
        String adapterNodeId = spec.adapterId() + "-node";
        declareExternalWorkerGroup(spec.workerGroupId(), "crawlerApp", "crawler.fetch-page", workerHeaders);
        bindExternalAdapterNode(adapterNodeId, spec.workerGroupId(), workerHeaders);
        Map<String, Object> response = exchange("/worker-api/v1/workers", HttpMethod.POST, Map.of(
                "workerId", spec.workerId(),
                "adapterNodeId", adapterNodeId,
                "workerGroupId", spec.workerGroupId(),
                "adapterId", spec.adapterId(),
                "transportHint", "realtime",
                "attributes", Map.of(
                        "lang", spec.language(),
                        "runtime", spec.runtimeLabel()
                )
        ), workerHeaders);
        assertApiOk(response);
        assertEquals(spec.adapterId(), responseData(response).get("adapterId"));
    }

    private AutoCloseable startWorker(ExternalWorkerCase spec) throws Exception {
        String baseUrl = "http://127.0.0.1:" + port;
        URI wsUri = URI.create("ws://127.0.0.1:" + WEBSOCKET_PORT + "/ws");
        return switch (spec.kind()) {
            case NODE_POLLING -> ExternalNodeWorkerProcess.startPollingSample(
                    baseUrl,
                    spec.workerId(),
                    spec.workerKey(),
                    spec.workerGroupId()
            );
            case JAVA_POLLING -> ExternalJavaWorkerProcess.startPollingSample(
                    baseUrl,
                    spec.workerId(),
                    spec.workerKey(),
                    spec.workerGroupId()
            );
            case NODE_WEBSOCKET -> ExternalNodeWorkerProcess.startWebSocketSample(spec.workerId(), wsUri);
            case JAVA_WEBSOCKET -> ExternalJavaWorkerProcess.startWebSocketSample(spec.workerId(), wsUri);
            case NODE_SOCKET -> ExternalNodeWorkerProcess.startSocketSample(
                    spec.workerId(),
                    "127.0.0.1",
                    waitForPositiveIntSystemProperty(
                            SocketTransportServer.BOUND_PORT_PROPERTY,
                            "Socket server did not publish a bound port",
                            20,
                            100L
                    ));
            case JAVA_SOCKET -> ExternalJavaWorkerProcess.startSocketSample(
                    spec.workerId(),
                    "127.0.0.1",
                    waitForPositiveIntSystemProperty(
                            SocketTransportServer.BOUND_PORT_PROPERTY,
                            "Socket server did not publish a bound port",
                            20,
                            100L
                    ));
        };
    }

    private void assertWorkerProcessAlive(AutoCloseable process, ExternalWorkerCase spec) {
        switch (spec.kind()) {
            case NODE_POLLING, NODE_WEBSOCKET, NODE_SOCKET ->
                    ((ExternalNodeWorkerProcess) process).assertAlive(spec.workerId() + " exited before reaching ONLINE");
            case JAVA_POLLING, JAVA_WEBSOCKET, JAVA_SOCKET ->
                    ((ExternalJavaWorkerProcess) process).assertAlive(spec.workerId() + " exited before reaching ONLINE");
        }
    }

    private String capturedOutput(AutoCloseable process) {
        return switch (process) {
            case ExternalNodeWorkerProcess node -> node.capturedOutput();
            case ExternalJavaWorkerProcess javaProcess -> javaProcess.capturedOutput();
            default -> "";
        };
    }

    private HttpHeaders credentialHeaders(String credential) {
        HttpHeaders headers = new HttpHeaders();
        headers.add(SdkCredentialAuthSupport.API_KEY_HEADER, credential);
        return headers;
    }

    private static Stream<ExternalWorkerCase> cases() {
        return Stream.of(
                new ExternalWorkerCase(WorkerKind.NODE_POLLING, "node-polling-public-contract", "node-parity-polling-001", "node-parity-polling-key", "polling", "node-polling-worker", "node-polling-crawler", "node", false),
                new ExternalWorkerCase(WorkerKind.JAVA_POLLING, "java-polling-public-contract", "java-parity-polling-001", "java-parity-polling-key", "polling", "java-polling-worker", "java-polling-crawler", "java", false),
                new ExternalWorkerCase(WorkerKind.NODE_WEBSOCKET, "node-websocket-public-contract", "node-parity-websocket-001", "node-parity-websocket-key", "websocket", "node-websocket-worker", "node-websocket-crawler", "node", true),
                new ExternalWorkerCase(WorkerKind.JAVA_WEBSOCKET, "java-websocket-public-contract", "java-parity-websocket-001", "java-parity-websocket-key", "websocket", "java-websocket-worker", "java-websocket-crawler", "java", true),
                new ExternalWorkerCase(WorkerKind.NODE_SOCKET, "node-socket-public-contract", "node-parity-socket-001", "node-parity-socket-key", "socket", "node-socket-worker", "node-socket-crawler", "node", true),
                new ExternalWorkerCase(WorkerKind.JAVA_SOCKET, "java-socket-public-contract", "java-parity-socket-001", "java-parity-socket-key", "socket", "java-socket-worker", "java-socket-crawler", "java", true)
        );
    }

    private enum WorkerKind {
        NODE_POLLING,
        JAVA_POLLING,
        NODE_WEBSOCKET,
        JAVA_WEBSOCKET,
        NODE_SOCKET,
        JAVA_SOCKET
    }

    private record ExternalWorkerCase(WorkerKind kind,
                                      String sourceRef,
                                      String workerId,
                                      String workerKey,
                                      String adapterId,
                                      String runtimeLabel,
                                      String workerGroupId,
                                      String language,
                                      boolean requiresPreRegistration) {
        @Override
        public String toString() {
            return kind.name().toLowerCase();
        }
    }
}
