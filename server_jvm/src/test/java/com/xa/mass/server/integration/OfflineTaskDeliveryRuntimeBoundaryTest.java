package com.xa.mass.server.integration;

import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity.HOT_ACQUIRE;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity.RECOVERY_RECHECK;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.STALE;
import static com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionStatus.TRANSITIONED;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.xa.mass.kernel.delivery.TaskEvidenceRuntime;
import com.xa.mass.kernel.delivery.TaskEvidenceRuntime.TaskEvidenceType;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreTransitionResult;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.server.assembly.pacer.KernelPacerAssembly;
import com.xa.mass.server.assembly.runtime.ServerConfiguredRuntimeLifecycleHost;
import com.xa.mass.server.task.call.TaskRpcResultProbe;
import com.xa.mass.server.testsupport.RedisTestScope;
import com.xa.mass.server.testsupport.ServerTestConfiguration;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.javase.JavaWorker;
import com.xa.mass.workerdelivery.json.Jsons;
import io.lettuce.core.RedisClient;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInstance;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

/** Actual TASK delivery must supply new evidence after the one-shot disconnect is lost. */
@ActiveProfiles({"test", "integration-test"})
@SpringBootTest(classes = ServerTestConfiguration.class,
        webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Tag("runtime-boundary")
@Import(OfflineTaskDeliveryRuntimeBoundaryTest.ObservationConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class OfflineTaskDeliveryRuntimeBoundaryTest {
    private static final int PORT = freePort();
    private static final int ADAPTER_PORT = freePort();
    private static final String ADAPTER = "offline-delivery-boundary";
    private static final String GROUP = "offline-delivery-workers";
    private static final String EVENT = "extension.worker.offline-delivery";
    private static final URI BASE = URI.create("http://127.0.0.1:" + PORT);
    private static final RedisTestScope SCOPE = RedisTestScope.create("offline_delivery");
    private final HttpClient http = HttpClient.newHttpClient();

    @MockitoSpyBean WorkerScoreCore scores;
    @MockitoSpyBean WorkerServiceabilityRuntime serviceability;
    @MockitoSpyBean WorkerCommandRuntime commands;
    @MockitoSpyBean TaskEvidenceRuntime taskEvidence;
    @Autowired KernelPacerAssembly pacer;
    @Autowired ServerConfiguredRuntimeLifecycleHost adapters;
    @Autowired TaskRpcResultProbe resultProbe;
    @Autowired BoundaryEvidence evidence;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("xa.mass.worker-matching.groups["+GROUP+"].pools[0]",()->"any");
        registry.add("xa.mass.worker-matching.groups["+GROUP+"].functions[0]",()->"worker.any");
        registry.add("xa.mass.worker-assembly.group-config-json", () -> Jsons.toJson(Map.of(GROUP, Map.of("eventCodes", List.of(EVENT)))));
        registry.add("xa.mass.project-assembly.projects[0].project-id", () -> "offline-boundary");
        registry.add("xa.mass.project-assembly.projects[0].worker-group-ids[0]", () -> GROUP);
        registry.add("server.port", () -> PORT);
        registry.add("xa.mass.redis.url", () -> REDIS_URL);
        registry.add("xa.mass.redis.scope", SCOPE::scope);
        // DEFAULT consumes network evidence but has no periodic probes that could mask this path.
        registry.add("xa.mass.kernel-pacer.preset", () -> "DEFAULT");
        registry.add("xa.mass.worker-delivery.adapter.remote-base-url", BASE::toString);
        registry.add("xa.mass.worker-endpoints.defaults.WEBSOCKET", () -> ADAPTER);
        String endpoint = "xa.mass.worker-endpoints.endpoints." + ADAPTER;
        registry.add(endpoint + ".transport-type", () -> "WEBSOCKET");
        registry.add(endpoint + ".public-uri", () -> "ws://127.0.0.1:" + ADAPTER_PORT
                + "/api/v1/worker-delivery/websocket");
        String prefix = "xa.mass.worker-delivery.adapter.instances." + ADAPTER;
        Map.ofEntries(Map.entry("type", "WEBSOCKET"), Map.entry("listen-host", "127.0.0.1"),
                Map.entry("listen-port", Integer.toString(ADAPTER_PORT)),
                Map.entry("command-backoff", "20ms"), Map.entry("command-consume-limit", "100"),
                Map.entry("command-retry-capacity", "1000"), Map.entry("report-backoff", "20ms"),
                Map.entry("report-queue-capacity", "1000"), Map.entry("send-time-limit", "5s"),
                Map.entry("reconnect-verification-retention", "10m"),
                Map.entry("maximum-disconnected-workers", "100000"),
                Map.entry("maximum-encoded-properties-bytes", "67108864"),
                Map.entry("shutdown-timeout", "5s"))
                .forEach((name, value) -> registry.add(prefix + "." + name, () -> value));
    }

    private boolean isHot(String workerId) {
        var state = scores.observeSchedulingStates(GROUP, List.of(workerId)).statesByWorkerId().get(workerId);
        return state == WorkerScoreCore.SchedulingState.HOT_SCORE_OVERDUE
                || state == WorkerScoreCore.SchedulingState.HELD_HOT;
    }

    @Test
    @SuppressWarnings("unchecked")
    void lostDisconnectIsReplacedByActualTaskDeliveryExpiryAndWorkRecovers() throws Exception {
        post("/api/v1/worker-groups/" + GROUP + ":register", Map.of("eventCodes", List.of(EVENT)));
        String task = (String) post("/api/v1/tasks", Map.of("projectId", "offline-boundary", "workerGroupId", GROUP, "maxRetryTimes", 3, "refill", List.of(Map.of("poolName","any","target",Map.of(),"count",100)))).get("taskId");
        var invoked = evidence.invoked;
        var lostDisconnects = evidence.lostDisconnects;
        var deliveryEvidence = evidence.deliveryEvidence;
        var rejectedDeliveries = evidence.rejectedDeliveries;
        var delivered = evidence.delivered;
        var candidateFence = evidence.candidateFence;
        var applied = evidence.applied;
        try (var worker = JavaWorker.create(BASE, GROUP, "offline-worker", WorkerTransportType.WEBSOCKET,
                Map::of, List.of(WorkerEventDefinition.extension("offline-delivery",
                        WorkerEventParameterResolvers.jsonMap(), input -> {
                            invoked.incrementAndGet();
                            return "completed";
                        })))) {
            worker.start();
            await("Worker preparation", () -> worker.snapshot().workerId() != null);
            String workerId = worker.snapshot().workerId();
            await("verified connected HOT baseline", () -> connectionState(workerId).equals("connected")
                    && isHot(workerId));
            evidence.workerId.set(workerId);

            worker.stop();
            await("disconnect consumed and deliberately lost", () -> lostDisconnects.get() == 1
                    && connectionState(workerId).equals("disconnected"));
            assertThat(isHot(workerId)).isTrue();
            post("/api/v1/tasks/" + task + "/items", List.of(Map.of("messageId", "offline-item", "eventCode", EVENT, "payload", Map.of(), "workerSelector", Map.of("executorName","worker.any","input",Map.of()))));
            post("/api/v1/tasks/" + task + "/approve", null);
            await("actual expiry evidence changes Score", () -> deliveryEvidence.get() > 0
                    && rejectedDeliveries.get() > 0 && applied.get() != null
                    && applied.get().status() == TRANSITIONED
                    && scores.observeSchedulingStates(GROUP, List.of(workerId)).statesByWorkerId().get(workerId) == WorkerScoreCore.SchedulingState.RECOVERY);

            assertThat(delivered).isNotEmpty();
            assertThat(invoked).hasValue(0);
            assertThat(scores.observeDueHotScoreCandidates(GROUP, null, 100)).isEmpty();
            assertThat(scores.acquireObservedHotScoreLeases(GROUP, Map.of(workerId, candidateFence.get()), System.currentTimeMillis() + 1_000).get(workerId).status()).isEqualTo(STALE);
            verify(serviceability, never()).offerProbeRequests(anyString(), anyList());

            worker.start();
            await("same Item succeeds after a real reconnect", () -> {
                Map<String, Object> result = (Map<String, Object>) post(
                        "/api/v1/tasks/" + task + "/results:load", List.of("offline-item")).get("offline-item");
                return "succeeded".equals(result.get("status"));
            });
            assertThat(worker.snapshot().workerId()).isEqualTo(workerId);
            assertThat(invoked.get()).isGreaterThan(0);
        } finally {
            try {
                post("/api/v1/tasks/" + task + "/close", null);
            } finally {
                // JUnit captures this bounded, content-free trace in the existing CI failure artifact.
                System.out.println("Offline TASK delivery evidence: " + evidence.trace);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class ObservationConfiguration {
        @Bean BoundaryEvidence offlineDeliveryEvidence() { return new BoundaryEvidence(); }

        @Bean SmartInitializingSingleton installOfflineDeliveryObservers(
                WorkerScoreCore scores, WorkerServiceabilityRuntime serviceability,
                WorkerCommandRuntime commands, TaskEvidenceRuntime taskEvidence,
                BoundaryEvidence evidence, KernelPacerAssembly pacer,
                ServerConfiguredRuntimeLifecycleHost adapters) {
            // Install all answers before Pacer and Adapter lifecycles can invoke these spies.
            return () -> {
                assertThat(pacer.isRunning()).isFalse();
                assertThat(adapters.isRunning()).isFalse();
                installSpyAnswers(scores, serviceability, commands, taskEvidence, evidence);
            };
        }
    }

    private static final class BoundaryEvidence {
        final AtomicReference<String> workerId = new AtomicReference<>("");
        final AtomicInteger invoked = new AtomicInteger();
        final AtomicInteger lostDisconnects = new AtomicInteger();
        final AtomicInteger deliveryEvidence = new AtomicInteger();
        final AtomicInteger rejectedDeliveries = new AtomicInteger();
        final List<DeliveryCommand> delivered = new CopyOnWriteArrayList<>();
        final AtomicReference<Long> candidateFence = new AtomicReference<>();
        final AtomicReference<WorkerScoreTransitionResult> applied = new AtomicReference<>();
        final List<String> trace = new CopyOnWriteArrayList<>();

        synchronized void record(String stage, Object value) {
            if (trace.size() < 100) trace.add(System.currentTimeMillis() + " " + stage + " " + value);
        }
    }

    @SuppressWarnings("unchecked")
    private static void installSpyAnswers(
            WorkerScoreCore scores, WorkerServiceabilityRuntime serviceability,
            WorkerCommandRuntime commands, TaskEvidenceRuntime taskEvidence,
            BoundaryEvidence evidence) {
        var lostDisconnects = evidence.lostDisconnects;
        var deliveryEvidence = evidence.deliveryEvidence;
        var rejectedDeliveries = evidence.rejectedDeliveries;
        var delivered = evidence.delivered;
        var candidateFence = evidence.candidateFence;
        var applied = evidence.applied;
        doAnswer(call -> {
            List<DeliveryReport> reports = (List<DeliveryReport>) call.callRealMethod();
            var retained = new ArrayList<DeliveryReport>();
            for (DeliveryReport report : reports) {
                boolean ours = report.src() == DeliveryEndpoint.ADAPTER && report.sourceId().equals(ADAPTER)
                        && evidence.workerId.get().equals(Jsons.parseObject(report.payload()).get("workerId"));
                if (ours && report.messageType().equals(ADAPTER_WORKER_CONNECTION_CHANGED)
                        && "DISCONNECTED".equals(Jsons.parseObject(report.payload()).get("state"))
                        && lostDisconnects.compareAndSet(0, 1)) {
                    evidence.record("DROP_FIRST_DISCONNECT", Jsons.parseObject(report.payload()).get("observedAtMillis"));
                    continue; // Test-only loss after real Adapter -> HTTP -> Redis delivery.
                }
                if (ours && report.messageType().equals(ADAPTER_WORKER_DELIVERY_EXPIRED)) {
                    deliveryEvidence.incrementAndGet();
                    evidence.record("CONSUME_DELIVERY_EXPIRED", Jsons.parseObject(report.payload()).get("observedAtMillis"));
                }
                retained.add(report);
            }
            return List.copyOf(retained);
        }).when(serviceability).consumeNetworkEvidenceResults(anyInt());
        doAnswer(call -> {
            Map<String, DeliveryCommand> result = (Map<String, DeliveryCommand>) call.callRealMethod();
            DeliveryCommand command = result.get(evidence.workerId.get());
            if (command != null && command.src() == DeliveryEndpoint.TASK) {
                delivered.add(command);
                evidence.record("COMMAND_HANDED_TO_ADAPTER deadline", command.executeBeforeMillis());
            }
            return result;
        }).when(commands).consumeWorkerCommands(eq(ADAPTER), anyInt());
        doAnswer(call -> {
            Map<String, Long> observed = call.getArgument(1);
            candidateFence.set(observed.get(evidence.workerId.get()));
            Object result = call.callRealMethod();
            evidence.record("EXACT_CONFIRM input=" + observed, result);
            return result;
        }).when(scores).acquireObservedHotScoreLeases(eq(GROUP), anyMap(), anyLong());
        doAnswer(call -> {
            Map<String, WorkerScoreTransitionResult> result =
                    (Map<String, WorkerScoreTransitionResult>) call.callRealMethod();
            evidence.record("NETWORK_SCORE target=" + call.getArgument(2) + " observed=" + call.getArgument(1), result);
            if (call.getArgument(2) == RECOVERY_RECHECK) applied.set(result.get(evidence.workerId.get()));
            return result;
        }).when(scores).rewriteCurrentPolarityWithinTimeFence(eq(GROUP), anyMap(), any(), org.mockito.ArgumentMatchers.anyLong());
        doAnswer(call -> {
            Object result = call.callRealMethod();
            evidence.record("SUCCESS_RELEASE input=" + call.getArgument(1), result);
            return result;
        }).when(scores).releaseObservedHotScoreHolds(eq(GROUP), anyMap(), anyLong());
        doAnswer(call -> {
            List<DeliveryReport> reports = call.getArgument(1);
            for (DeliveryReport report : reports) {
                if (report.messageType().equals(ADAPTER_COMMAND_DELIVERY_FAILED)
                        && delivered.stream().anyMatch(command -> command.forward().equals(report.forward()))) {
                    rejectedDeliveries.incrementAndGet();
                    evidence.record("CORRELATED_ADAPTER_DELIVERY_FAILED", report.diagnosticCode());
                }
            }
            return call.callRealMethod();
        }).when(taskEvidence).appendTaskEvidence(eq(TaskEvidenceType.EXECUTION_FAILURE), anyList());
    }

    private String connectionState(String workerId) throws Exception {
        Map<?, ?> states = (Map<?, ?>) post("/api/v1/runtime-view/endpoint-managers/" + ADAPTER
                + "/workers:network-observe", List.of(workerId)).get("statesByWorkerId");
        return (String) states.get(workerId);
    }

    private Map<String, Object> post(String path, Object body) throws Exception {
        var request = HttpRequest.newBuilder(BASE.resolve(path)).timeout(Duration.ofSeconds(5))
                .header("Content-Type", "application/json").POST(body == null
                        ? HttpRequest.BodyPublishers.noBody()
                        : HttpRequest.BodyPublishers.ofString(Jsons.toJson(body))).build();
        var response = http.send(request, HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("POST %s; evidence=%s", path, evidence.trace).isEqualTo(200);
        return response.body().isBlank() ? Map.of() : Jsons.parseObject(response.body());
    }

    private void await(String stage, Check check) throws Exception {
        long deadline = System.nanoTime() + Duration.ofSeconds(15).toNanos();
        while (System.nanoTime() < deadline) {
            if (check.done()) return;
            Thread.sleep(20);
        }
        throw new AssertionError(stage + "; evidence=" + evidence.trace);
    }

    @FunctionalInterface private interface Check { boolean done() throws Exception; }

    @AfterAll
    void cleanup() {
        try {
            adapters.stop();
        } finally {
            try {
                resultProbe.stop();
                pacer.stop();
            } finally {
                http.close();
                RedisClient client = RedisClient.create(REDIS_URL);
                try (var connection = client.connect()) {
                    SCOPE.cleanup(connection.sync());
                } finally {
                    client.shutdown();
                }
            }
        }
    }

    private static int freePort() {
        try (var socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException failure) {
            throw new IllegalStateException(failure);
        }
    }
}
