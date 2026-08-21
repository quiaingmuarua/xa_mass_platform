package com.xa.mass.server.integration;

import static com.xa.mass.server.testsupport.ServerIntegrationProfile.KERNEL_BASE_URL;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_PREFIX;
import static com.xa.mass.server.testsupport.ServerIntegrationProfile.REDIS_URL;
import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.WorkerCommandDispatcher;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerManagementEventDefinitions;
import com.xa.mass.worker.javase.JavaWorker;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScorePolarity;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerPointClient;
import com.xa.mass.workerdelivery.json.Jsons;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles({"test", "integration-test"})
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Tag("runtime-boundary")
class RuntimeApiPythonIntegrationTest {

    private static final int SERVER_PORT = availablePort();
    private static final int[] ACTIVE_ADAPTER_PORTS =
            availablePorts(2);
    private static final String WEBSOCKET_ENDPOINT_MANAGER_ID =
            "java-websocket-integration";
    private static final String SOCKET_ENDPOINT_MANAGER_ID =
            "java-socket-integration";
    private static final String TEST_CAPABILITY =
            "test.integration.observe";
    private static final String TEST_EVENT_CODE =
            "extension.worker." + TEST_CAPABILITY;
    private static final String DIRECT_CAPABILITY =
            "test.integration.direct-snapshot";
    private static final String DIRECT_EVENT_CODE =
            "extension.worker." + DIRECT_CAPABILITY;
    private static final String SERVICEABILITY_WORKER_GROUP_ID =
            "serviceability-runtime-boundary";
    private static final String TEST_RESULT = "{\"observed\":\"input\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @Autowired
    private WorkerScoreCore workerScores;

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "server.port",
                () -> Integer.toString(SERVER_PORT)
        );
        registry.add(
                "xa.mass.worker-delivery.adapter.http-client.base-url",
                () -> "http://127.0.0.1:" + SERVER_PORT
        );
        registry.add(
                "xa.mass.worker-delivery.adapter.http-client.request-timeout",
                () -> "2s"
        );
        registry.add(
                "xa.mass.worker-binding.endpoints.system-polling."
                        + "transport-type",
                () -> "POLLING"
        );
        registry.add(
                "xa.mass.worker-binding.endpoints.system-polling.public-uri",
                () -> "http://127.0.0.1:" + SERVER_PORT
        );
        addWebSocketAdapter(
                registry,
                WEBSOCKET_ENDPOINT_MANAGER_ID,
                ACTIVE_ADAPTER_PORTS[0]
        );
        addSocketAdapter(
                registry,
                SOCKET_ENDPOINT_MANAGER_ID,
                ACTIVE_ADAPTER_PORTS[1]
        );
    }

    @Test
    void finitePrecomputedClosesThroughTheJavaPollingWorker()
            throws Exception {
        runWorkerDeliveryClosure(
                "FINITE_PRECOMPUTED",
                TransportProfile.POLLING
        );
    }

    @Test
    void reusableDirectClosesThroughWebSocketAndSocketAdapters()
            throws Exception {
        runWorkerDeliveryClosure(
                "REUSABLE_DIRECT",
                TransportProfile.WEBSOCKET
        );
        runWorkerDeliveryClosure(
                "REUSABLE_DIRECT",
                TransportProfile.SOCKET
        );
    }

    @Test
    void explicitWorkerSchedulingUsesCanonicalWorkerAndPlatformProperties()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "property-tools-" + suffix;
        String clientWorkerKey = "property-worker-" + suffix;
        String taskId = "property-task-" + suffix;

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId,
                """
                        {
                          "eventCodes": ["%s"]
                        }
                        """.formatted(TEST_EVENT_CODE)
        ).statusCode()).isEqualTo(200);
        BoundWorker boundWorker = registerAndBindWorker(
                workerGroupId,
                clientWorkerKey,
                TransportProfile.WEBSOCKET,
                Map.of("region", "cn-east")
        );
        String workerId = boundWorker.workerId();

        RunningWorker worker = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("region", "cn-east"),
                TransportProfile.WEBSOCKET
        );
        try {
            awaitWorkerRegistered(workerGroupId, workerId);
            assertThat(send(
                    "PATCH",
                    "/api/v1/worker-groups/" + workerGroupId
                            + "/workers/" + workerId
                            + "/platform-properties",
                    "{\"properties\":{\"pool\":\"batch\"}}"
            ).statusCode()).isEqualTo(200);
            assertThat(send(
                    "POST",
                    "/api/v1/tasks",
                    taskRequest(taskId, workerGroupId, "REUSABLE_DIRECT")
            ).statusCode()).isEqualTo(201);
            String firstMessageId = "property-message-1-" + suffix;
            String secondMessageId = "property-message-2-" + suffix;
            String propertyRule = "{"
                    + "\"workerId\":{\"$eq\":\"" + workerId + "\"},"
                    + "\"worker.region\":{\"$eq\":\"cn-east\"},"
                    + "\"platform.pool\":{\"$in\":[\"batch\"]}"
                    + "}";
            appendItemWithAllocationRule(
                    taskId,
                    firstMessageId,
                    propertyRule
            );
            appendItemWithAllocationRule(
                    taskId,
                    secondMessageId,
                    propertyRule
            );
            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/approve",
                    null
            ).statusCode()).isEqualTo(200);

            awaitStoredResult(taskId, firstMessageId);
            awaitStoredResult(taskId, secondMessageId);

            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/close",
                    null
            ).statusCode()).isEqualTo(200);
        } finally {
            worker.close();
        }
    }

    @Test
    void directWorkerAndAdapterCallsTraverseWebSocketAdapterWithoutPause()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "direct-tools-" + suffix;
        String clientWorkerKey = "direct-worker-" + suffix;

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId,
                """
                        {
                          "eventCodes": ["%s", "%s"]
                        }
                        """.formatted(TEST_EVENT_CODE, DIRECT_EVENT_CODE)
        ).statusCode()).isEqualTo(200);
        BoundWorker boundWorker = registerAndBindWorker(
                workerGroupId,
                clientWorkerKey,
                TransportProfile.WEBSOCKET,
                Map.of("runtime", "java-direct")
        );
        String workerId = boundWorker.workerId();
        RunningWorker worker = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("runtime", "java-direct"),
                TransportProfile.WEBSOCKET
        );
        try {
            awaitWorkerRegistered(workerGroupId, workerId);
            assertThat(observedDirectPayload(workerDirectCall(
                    workerGroupId,
                    workerId,
                    DIRECT_EVENT_CODE,
                    "{}"
            ), workerId, "200"))
                    .isEqualTo("{\"direct\":\"observed\"}");

            assertThat(Jsons.parseObject(
                    observedDirectPayload(
                            adapterDirectCall(
                                    "platform.adapter.probe",
                                    "null"
                            ),
                            WEBSOCKET_ENDPOINT_MANAGER_ID,
                            "200"
                    )
            )).containsEntry("adapterId", WEBSOCKET_ENDPOINT_MANAGER_ID)
                    .containsEntry("reachable", true);

            assertThat(Jsons.parseObject(
                    observedDirectPayload(workerDirectCall(
                            workerGroupId,
                            workerId,
                            WorkerManagementEventDefinitions.PROBE_EVENT,
                            "null"
                    ), workerId, "200")
            )).containsEntry("reachable", true);

            assertThat(Jsons.parseObject(
                    observedDirectPayload(workerDirectCall(
                            workerGroupId,
                            workerId,
                            WorkerManagementEventDefinitions
                                    .PROPERTIES_SNAPSHOT_EVENT,
                            "null"
                    ), workerId, "200")
            )).containsEntry(
                    "properties",
                    Map.of("runtime", "java-direct")
            );

            assertConnectionState(workerId, "CONNECTED");
            assertWorkerProperties(workerId);
            assertThat(Jsons.parseObject(
                    observedDirectPayload(
                            adapterDirectCall(
                                    "platform.adapter.worker-connections.close-current",
                                    workerIdsPayload(workerId)
                            ),
                            WEBSOCKET_ENDPOINT_MANAGER_ID,
                            "200"
                    )
            )).containsEntry(
                    "outcomeByWorkerId",
                    Map.of(workerId, "close-started")
            );

            assertThat(Jsons.parseObject(
                    observedDirectPayload(workerDirectCall(
                            workerGroupId,
                            workerId,
                            WorkerManagementEventDefinitions.PROBE_EVENT,
                            "null"
                    ), workerId, "200")
            )).containsEntry("reachable", true);
            assertConnectionState(workerId, "CONNECTED");
            assertWorkerProperties(workerId);
        } finally {
            worker.close();
        }
    }

    @Test
    void exactRouteEvidenceConvergesWorkerScoreThroughKernelResultPacer()
            throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = SERVICEABILITY_WORKER_GROUP_ID;
        String clientWorkerKey = "serviceability-worker-" + suffix;

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId,
                """
                        {
                          "eventCodes": ["%s"]
                        }
                        """.formatted(TEST_EVENT_CODE)
        ).statusCode()).isEqualTo(200);
        BoundWorker boundWorker = registerAndBindWorker(
                workerGroupId,
                clientWorkerKey,
                TransportProfile.WEBSOCKET,
                Map.of("runtime", "serviceability-e2e")
        );
        String workerId = boundWorker.workerId();
        awaitWorkerRegistered(workerGroupId, workerId);

        WorkerScoreState initial = awaitWorkerScore(
                workerGroupId,
                workerId,
                WorkerScorePolarity.HOT_ACQUIRE
        );

        RunningWorker first = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("runtime", "serviceability-e2e"),
                TransportProfile.WEBSOCKET
        );
        RunningWorker reconnected = null;
        String demandTaskId = "serviceability-demand-" + suffix;
        boolean demandTaskCreated = false;
        try {
            awaitConnectionState(workerId, "CONNECTED");
            WorkerScoreState connected = awaitWorkerScore(
                    workerGroupId,
                    workerId,
                    WorkerScorePolarity.HOT_ACQUIRE
            );
            assertThat(connected.timeMillis()).isEqualTo(initial.timeMillis());

            first.close();
            first = null;
            awaitConnectionState(workerId, "DISCONNECTED");
            WorkerScoreState disconnected = awaitWorkerScore(
                    workerGroupId,
                    workerId,
                    WorkerScorePolarity.RECOVERY_RECHECK
            );
            assertThat(disconnected.timeMillis())
                    .isEqualTo(connected.timeMillis());

            reconnected = startWorker(
                    workerGroupId,
                    clientWorkerKey,
                    workerId,
                    boundWorker.endpointUri(),
                    Map.of("runtime", "serviceability-e2e"),
                    TransportProfile.WEBSOCKET
            );
            awaitConnectionState(workerId, "CONNECTED");
            WorkerScoreState restored = awaitWorkerScore(
                    workerGroupId,
                    workerId,
                    WorkerScorePolarity.HOT_ACQUIRE
            );
            assertThat(restored.timeMillis())
                    .isEqualTo(disconnected.timeMillis());
            assertThat(restored.laneRank())
                    .isEqualTo(WorkerScoreCore.MIN_LANE_RANK);

            assertThat(send(
                    "POST",
                    "/api/v1/tasks",
                    taskRequest(
                            demandTaskId,
                            workerGroupId,
                            "REUSABLE_DIRECT"
                    )
            ).statusCode()).isEqualTo(201);
            demandTaskCreated = true;
            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + demandTaskId + "/approve",
                    null
            ).statusCode()).isEqualTo(200);
            appendItemWithAllocationRule(
                    demandTaskId,
                    "serviceability-demand-item-" + suffix,
                    "{\"workerId\":{\"$eq\":\"missing-worker-"
                            + suffix + "\"}}"
            );
            awaitServiceabilitySnapshot(workerGroupId, workerId);
        } finally {
            if (demandTaskCreated) {
                send(
                        "POST",
                        "/api/v1/tasks/" + demandTaskId + "/close",
                        null
                );
            }
            if (first != null) {
                first.close();
            }
            if (reconnected != null) {
                reconnected.close();
            }
        }
    }

    private HttpResponse<String> workerDirectCall(
            String workerGroupId,
            String workerId,
            String eventCode,
            String opaquePayload
    ) throws Exception {
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("workerGroupId", workerGroupId);
        request.put("workerPayloads", Map.of(workerId, opaquePayload));
        request.put("messageType", eventCode);
        request.put("waitTimeoutMillis", 3_000);
        return send(
                "POST",
                "/api/v1/worker-delivery/endpoint-managers/"
                        + WEBSOCKET_ENDPOINT_MANAGER_ID
                        + "/direct-calls",
                JSON.writeValueAsString(request)
        );
    }

    private HttpResponse<String> adapterDirectCall(
            String eventCode,
            String opaquePayload
    ) throws Exception {
        return send(
                "POST",
                "/api/v1/worker-delivery/endpoint-managers/"
                        + WEBSOCKET_ENDPOINT_MANAGER_ID
                        + "/direct-calls",
                JSON.writeValueAsString(Map.of(
                        "messageType", eventCode,
                        "opaquePayload", opaquePayload,
                        "waitTimeoutMillis", 3_000
                ))
        );
    }

    private static String observedDirectPayload(
            HttpResponse<String> response,
            String targetId,
            String outcomeCode
    ) throws Exception {
        assertThat(response.statusCode()).isEqualTo(200);
        var target = JSON.readTree(response.body())
                .get("results")
                .get(targetId);
        assertThat(target.get("status").asText()).isEqualTo("observed");
        assertThat(target.get("outcomeCode").asText())
                .isEqualTo(outcomeCode);
        return target.get("opaqueResultPayload").asText();
    }

    private void assertConnectionState(String workerId, String state)
            throws Exception {
        assertThat(connectionState(workerId)).isEqualTo(state);
    }

    private String connectionState(String workerId) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/runtime-view/endpoint-managers/"
                        + WEBSOCKET_ENDPOINT_MANAGER_ID
                        + "/workers:network-observe",
                JSON.writeValueAsString(Map.of(
                        "workerIds",
                        List.of(workerId)
                ))
        );
        assertThat(response.statusCode()).isEqualTo(200);
        var payload = JSON.readTree(response.body());
        assertThat(payload.get("endpointManagerId").asText())
                .isEqualTo(WEBSOCKET_ENDPOINT_MANAGER_ID);
        assertThat(payload.get("readAt").asText()).isNotBlank();
        return payload.get("statesByWorkerId")
                .get(workerId)
                .asText()
                .toUpperCase(java.util.Locale.ROOT);
    }

    @SuppressWarnings("unchecked")
    private void assertWorkerProperties(String workerId) throws Exception {
        Map<String, Object> payload = Jsons.parseObject(
                observedDirectPayload(
                        adapterDirectCall(
                                "platform.adapter.worker-properties.snapshot",
                                workerIdsPayload(workerId)
                        ),
                        WEBSOCKET_ENDPOINT_MANAGER_ID,
                        "200"
                )
        );
        Map<String, Object> propertiesByWorkerId =
                (Map<String, Object>) payload.get(
                        "propertiesByWorkerId"
                );
        Map<String, Object> observation =
                (Map<String, Object>) propertiesByWorkerId.get(workerId);
        assertThat(observation)
                .doesNotContainKeys(
                        "workerGroupId",
                        "connectionState",
                        "freshness",
                        "version",
                        "observedAtMillis"
                )
                .containsEntry(
                        "properties",
                        Map.of("runtime", "java-direct")
                );
        assertThat(observation.get("updatedAtMillis")).isNotNull();
    }

    private static String workerIdsPayload(String workerId) {
        return Jsons.toJson(Map.of(
                "workerIds",
                List.of(workerId)
        ));
    }

    private void awaitServiceabilitySnapshot(
            String workerGroupId,
            String workerId
    ) throws Exception {
        WorkerScoreState before = awaitWorkerScore(
                workerGroupId,
                workerId,
                WorkerScorePolarity.HOT_ACQUIRE
        );
        RedisClient client = RedisClient.create(REDIS_URL);
        try (var connection = client.connect(StringCodec.UTF8)) {
            var redis = connection.sync();
            String scoreKey = "wr:" + redisPrefix()
                    + ":score:" + workerGroupId;
            // Fixture-only: leave the Route connected while making the
            // periodic snapshot, rather than another Route transition, own
            // the RECOVERY -> HOT proof below.
            redis.zadd(scoreKey, -before.score(), workerId);
            WorkerScoreState recovery = awaitWorkerScore(
                    workerGroupId,
                    workerId,
                    WorkerScorePolarity.RECOVERY_RECHECK
            );
            assertThat(recovery.timeMillis()).isEqualTo(before.timeMillis());

        } finally {
            client.shutdown();
        }
        WorkerScoreState after = awaitWorkerScore(
                workerGroupId,
                workerId,
                WorkerScorePolarity.HOT_ACQUIRE
        );
        assertThat(after.score()).isEqualTo(before.score());
    }

    private void awaitConnectionState(String workerId, String expectedState)
            throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            if (expectedState.equals(connectionState(workerId))) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "Worker connection state did not become " + expectedState
        );
    }

    private WorkerScoreState awaitWorkerScore(
            String workerGroupId,
            String workerId,
            WorkerScorePolarity expectedPolarity
    ) throws InterruptedException {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(5).toNanos();
        while (System.nanoTime() < deadline) {
            WorkerScoreState state = workerScores.getScoreStates(
                    workerGroupId,
                    List.of(workerId)
            ).get(workerId);
            if (state != null
                    && state.polarity() == expectedPolarity) {
                return state;
            }
            Thread.sleep(20);
        }
        throw new AssertionError(
                "Worker score did not become " + expectedPolarity
        );
    }

    private static String redisPrefix() {
        return REDIS_PREFIX;
    }

    @Test
    void pythonCommandApiExposesOnlyTaskCommands() throws Exception {
        assertThat(sendKernel(
                "POST",
                "/tasks/missing/items",
                "{\"items\":[]}"
        ).statusCode()).isEqualTo(404);
        assertThat(sendKernel(
                "PUT",
                "/worker-groups/missing",
                "{\"eventCodes\":[]}"
        ).statusCode()).isEqualTo(404);
        assertThat(sendKernel(
                "PUT",
                "/worker-groups/missing/workers/worker-1",
                "{\"endpointManagerId\":\"system-polling\"}"
        ).statusCode()).isEqualTo(404);
    }

    private void runWorkerDeliveryClosure(
            String taskProfile,
            TransportProfile transportProfile
    ) throws Exception {
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "integration-tools-" + suffix;
        String clientWorkerKey = "worker-" + suffix;
        String taskId = "task-" + suffix;
        String firstMessageId = "message-1-" + suffix;
        String secondMessageId = "message-2-" + suffix;

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId,
                """
                        {
                          "eventCodes": ["%s"]
                        }
                        """.formatted(TEST_EVENT_CODE)
        ).statusCode()).isEqualTo(200);

        BoundWorker boundWorker = registerAndBindWorker(
                workerGroupId,
                clientWorkerKey,
                transportProfile,
                Map.of("runtime", "java")
        );
        String workerId = boundWorker.workerId();

        RunningWorker worker = startWorker(
                workerGroupId,
                clientWorkerKey,
                workerId,
                boundWorker.endpointUri(),
                Map.of("runtime", "java"),
                transportProfile
        );
        try {
            awaitWorkerRegistered(workerGroupId, workerId);
            assertThat(send(
                    "POST",
                    "/api/v1/tasks",
                    taskRequest(taskId, workerGroupId, taskProfile)
            ).statusCode()).isEqualTo(201);
            appendItem(
                    taskId,
                    firstMessageId,
                    workerId,
                    "REUSABLE_DIRECT".equals(taskProfile)
            );
            appendItem(
                    taskId,
                    secondMessageId,
                    workerId,
                    "REUSABLE_DIRECT".equals(taskProfile)
            );
            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/approve",
                    null
            ).statusCode()).isEqualTo(200);

            awaitStoredResult(taskId, firstMessageId);
            awaitStoredResult(taskId, secondMessageId);

            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/close",
                    null
            ).statusCode()).isEqualTo(200);
        } finally {
            worker.close();
        }
    }

    private void appendItemWithAllocationRule(
            String taskId,
            String messageId,
            String allocationRule
    ) throws Exception {
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/tasks/" + taskId + "/items",
                """
                        {
                          "items": [{
                            "messageId": "%s",
                            "eventCode": "%s",
                            "createdAtMillis": %d,
                            "payload": {"value": "input"},
                            "allocationRule": %s
                          }]
                        }
                        """.formatted(
                        messageId,
                        TEST_EVENT_CODE,
                        System.currentTimeMillis() - 1_000,
                        allocationRule
                )
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"appended\"");
    }

    private void assertStoredItemAndFinalSuccess(
            String taskId,
            String messageId
    ) {
        RedisClient client = RedisClient.create(REDIS_URL);
        try (var connection = client.connect(StringCodec.UTF8)) {
            var redis = connection.sync();
            String prefix = System.getenv().getOrDefault(
                    "KERNEL_DESIGN_REDIS_PREFIX",
                    "default"
            );
            assertThat(redis.hexists(
                    "tr:" + prefix + ":task:" + taskId + ":items",
                    messageId
            )).isTrue();
            Double score = redis.zscore(
                    "tr:" + prefix + ":task:" + taskId + ":item-score",
                    messageId
            );
            assertThat(score).isNotNull();
            assertThat(score.longValue()
                    / TaskItemScoreBandCore.TAG_FACTOR)
                    .isEqualTo(TaskItemScoreBandCore.FINAL_SUCCESS_TAG);
        } finally {
            client.shutdown();
        }
    }

    private void appendItem(
            String taskId,
            String messageId,
            String workerId,
            boolean directAllocation
    ) throws Exception {
        String allocationRule = directAllocation
                ? ",\"allocationRule\":{\"workerId\":{\"$eq\":\""
                + workerId + "\"}}"
                : "";
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/tasks/" + taskId + "/items",
                """
                        {
                          "items": [{
                            "messageId": "%s",
                            "eventCode": "%s",
                            "createdAtMillis": %d,
                            "payload": {"value": "input"}%s
                          }]
                        }
                        """.formatted(
                        messageId,
                        TEST_EVENT_CODE,
                        System.currentTimeMillis() - 1_000,
                        allocationRule
                )
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"appended\"");
    }

    private RunningWorker startWorker(
            String workerGroupId,
            String clientWorkerKey,
            String workerId,
            URI serverUrl,
            Map<String, Object> workerProperties,
            TransportProfile transportProfile
    ) throws Exception {
        List<WorkerEventDefinition<?>> definitions =
                List.of(
                        WorkerEventDefinition.extension(
                                TEST_CAPABILITY,
                                WorkerEventParameterResolvers.jsonMap(),
                                payload ->
                                Jsons.toJson(
                                        Map.of(
                                                "observed",
                                                payload.get("value")
                                        )
                                )
                        ),
                        WorkerEventDefinition.extension(
                                DIRECT_CAPABILITY,
                                WorkerEventParameterResolvers.jsonMap(),
                                payload -> "{\"direct\":\"observed\"}"
                        )
                );
        return switch (transportProfile) {
            case WEBSOCKET -> startTextMessageWorker(
                    workerGroupId,
                    clientWorkerKey,
                    workerId,
                    workerProperties,
                    definitions,
                    WorkerTransportType.WEBSOCKET
            );
            case SOCKET -> startTextMessageWorker(
                    workerGroupId,
                    clientWorkerKey,
                    workerId,
                    workerProperties,
                    definitions,
                    WorkerTransportType.SOCKET
            );
            case POLLING -> new PollingWorkerHandle(
                    new PollingWorkerTransport(
                            new OkHttpWorkerPointClient(
                                    serverUrl,
                                    Duration.ofSeconds(2)
                            ),
                            workerId,
                            WorkerCommandDispatcher.forWorker(definitions)
                    )
            );
        };
    }

    private RunningWorker startTextMessageWorker(
            String workerGroupId,
            String clientWorkerKey,
            String workerId,
            Map<String, Object> workerProperties,
            List<WorkerEventDefinition<?>> definitions,
            WorkerTransportType transportType
    ) {
        JavaWorker worker = JavaWorker.create(
                URI.create("http://127.0.0.1:" + port),
                workerGroupId,
                clientWorkerKey,
                fixedIdentity(workerId),
                transportType,
                () -> workerProperties,
                definitions,
                WorkerConnectionOptions.of(
                        Duration.ofSeconds(2),
                        connectionPolicy()
                )
        );
        return new TextMessageWorkerHandle(worker);
    }

    private static TextMessageReconnectPolicy connectionPolicy() {
        return TextMessageReconnectPolicy.of(
                20,
                Duration.ofMillis(20),
                Duration.ofSeconds(1)
        );
    }

    private static WorkerIdentityStore fixedIdentity(String workerId) {
        return new WorkerIdentityStore() {
            @Override
            public Optional<String> loadWorkerId() {
                return Optional.of(workerId);
            }

            @Override
            public void saveWorkerId(String value) {
                throw new IllegalStateException(
                        "Existing integration identity must be reused"
                );
            }
        };
    }

    private BoundWorker registerAndBindWorker(
            String workerGroupId,
            String clientWorkerKey,
            TransportProfile profile,
            Map<String, Object> workerProperties
    ) throws Exception {
        Map<String, Object> completeProperties =
                new LinkedHashMap<>(workerProperties);
        completeProperties.put("clientWorkerKey", clientWorkerKey);
        String propertiesJson = JSON.writeValueAsString(
                completeProperties
        );
        HttpResponse<String> registerResponse = send(
                "POST",
                "/api/v1/worker-groups/" + workerGroupId
                        + "/workers:register",
                "{\"workerProperties\":" + propertiesJson + "}"
        );
        assertThat(registerResponse.statusCode()).isEqualTo(200);
        String workerId = JSON.readTree(registerResponse.body())
                .get("workerId")
                .asText();
        String transportType = switch (profile) {
            case POLLING -> "POLLING";
            case WEBSOCKET -> "WEBSOCKET";
            case SOCKET -> "SOCKET";
        };
        HttpResponse<String> bindResponse = send(
                "POST",
                "/api/v1/worker-groups/" + workerGroupId
                        + "/workers/" + workerId + ":bind",
                "{\"transportType\":\"" + transportType
                        + "\",\"workerProperties\":"
                        + propertiesJson + "}"
        );
        assertThat(bindResponse.statusCode()).isEqualTo(200);
        URI endpointUri = URI.create(
                JSON.readTree(bindResponse.body())
                        .get("endpointUri")
                        .asText()
        );
        return new BoundWorker(workerId, endpointUri);
    }

    private void awaitWorkerRegistered(
            String workerGroupId,
            String workerId
    ) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send(
                    "PATCH",
                    "/api/v1/worker-groups/" + workerGroupId
                            + "/workers/" + workerId
                            + "/platform-properties",
                    "{\"properties\":{}}"
            );
            if (response.statusCode() == 200) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Worker Bind was not applied to Kernel");
    }

    private void awaitStoredResult(
            String taskId,
            String messageId
    ) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(8).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/results:load",
                    "{\"messageIds\":[\"" + messageId + "\"]}"
            );
            if (response.statusCode() == 200) {
                String result = JSON.readTree(response.body())
                        .get("results")
                        .get(messageId)
                        .stringValue();
                if (TEST_RESULT.equals(result)) {
                    return;
                }
            }
            Thread.sleep(20);
        }
        throw new AssertionError("TaskItem success result was not stored");
    }

    private String taskRequest(
            String taskId,
            String workerGroupId,
            String taskProfile
    ) {
        String allocationRule = "FINITE_PRECOMPUTED".equals(taskProfile)
                ? "\"allocationRule\":{},"
                : "";
        return """
                {
                  "taskId": "%s",
                  "workerGroupId": "%s",
                  "profile": "%s",
                  %s
                  "config": {
                    "priority": "0",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3"
                  }
                }
                """.formatted(
                taskId,
                workerGroupId,
                taskProfile,
                allocationRule
        );
    }

    private HttpResponse<String> send(
            String method,
            String path,
            String body
    ) throws Exception {
        HttpRequest.BodyPublisher publisher = body == null
                ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(
                        body,
                        StandardCharsets.UTF_8
                );
        HttpRequest.Builder request = HttpRequest.newBuilder()
                .uri(URI.create("http://127.0.0.1:" + port + path))
                .method(method, publisher);
        if (body != null) {
            request.header("Content-Type", "application/json");
        }
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                        request.build(),
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );
    }

    private static HttpResponse<String> sendKernel(
            String method,
            String path,
            String body
    ) throws Exception {
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(KERNEL_BASE_URL + path))
                .header("Content-Type", "application/json")
                .method(
                        method,
                        HttpRequest.BodyPublishers.ofString(
                                body,
                                StandardCharsets.UTF_8
                        )
                )
                .build();
        return HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );
    }

    private static int availablePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (java.io.IOException error) {
            throw new IllegalStateException(
                    "Could not reserve an integration-test port",
                    error
            );
        }
    }

    private static int[] availablePorts(int count) {
        ServerSocket[] sockets = new ServerSocket[count];
        try {
            int[] ports = new int[count];
            for (int index = 0; index < count; index++) {
                sockets[index] = new ServerSocket(0);
                ports[index] = sockets[index].getLocalPort();
            }
            return ports;
        } catch (java.io.IOException error) {
            throw new IllegalStateException(
                    "Could not reserve integration-test Adapter ports",
                    error
            );
        } finally {
            for (ServerSocket socket : sockets) {
                if (socket == null) {
                    continue;
                }
                try {
                    socket.close();
                } catch (java.io.IOException ignored) {
                    // Best-effort release during static test setup.
                }
            }
        }
    }

    private static void addWebSocketAdapter(
            DynamicPropertyRegistry registry,
            String adapterId,
            int listenPort
    ) {
        String prefix = "xa.mass.worker-delivery.adapter.instances."
                + adapterId;
        registry.add(prefix + ".type", () -> "WEBSOCKET");
        registry.add(prefix + ".listen-host", () -> "127.0.0.1");
        registry.add(
                prefix + ".listen-port",
                () -> Integer.toString(listenPort)
        );
        addAdapterProcesses(registry, prefix);
        String endpointPrefix = "xa.mass.worker-binding.endpoints."
                + adapterId;
        registry.add(
                endpointPrefix + ".transport-type",
                () -> "WEBSOCKET"
        );
        registry.add(
                endpointPrefix + ".public-uri",
                () -> "ws://127.0.0.1:" + listenPort
                        + "/api/v1/worker-delivery/websocket"
        );
    }

    private static void addSocketAdapter(
            DynamicPropertyRegistry registry,
            String adapterId,
            int listenPort
    ) {
        String prefix = "xa.mass.worker-delivery.adapter.instances."
                + adapterId;
        registry.add(prefix + ".type", () -> "SOCKET");
        registry.add(prefix + ".listen-host", () -> "127.0.0.1");
        registry.add(
                prefix + ".listen-port",
                () -> Integer.toString(listenPort)
        );
        addAdapterProcesses(registry, prefix);
        String endpointPrefix = "xa.mass.worker-binding.endpoints."
                + adapterId;
        registry.add(
                endpointPrefix + ".transport-type",
                () -> "SOCKET"
        );
        registry.add(
                endpointPrefix + ".public-uri",
                () -> "tcp://127.0.0.1:" + listenPort
        );
    }

    private static void addAdapterProcesses(
            DynamicPropertyRegistry registry,
            String prefix
    ) {
        registry.add(
                prefix + ".processes[0].type",
                () -> "DELIVERY_COMMAND"
        );
        registry.add(prefix + ".processes[0].interval", () -> "20ms");
        registry.add(
                prefix + ".processes[0].consume-limit",
                () -> "100"
        );
        registry.add(
                prefix + ".processes[0].queue-capacity",
                () -> "1000"
        );
        registry.add(
                prefix + ".processes[1].type",
                () -> "DELIVERY_REPORT"
        );
        registry.add(prefix + ".processes[1].interval", () -> "20ms");
        registry.add(
                prefix + ".processes[1].queue-capacity",
                () -> "1000"
        );
    }

    private interface RunningWorker extends AutoCloseable {

        @Override
        void close();
    }

    private record BoundWorker(String workerId, URI endpointUri) {
    }

    private enum TransportProfile {
        POLLING,
        WEBSOCKET,
        SOCKET
    }

    private static final class PollingWorkerHandle
            implements RunningWorker {

        private final PollingWorkerTransport transport;
        private final Thread thread;

        private PollingWorkerHandle(PollingWorkerTransport transport) {
            this.transport = transport;
            thread = Thread.ofPlatform()
                    .name("integration-polling-worker")
                    .daemon(true)
                    .start(() -> {
                        while (!Thread.currentThread().isInterrupted()) {
                            try {
                                if (!transport.runOnce()) {
                                    Thread.sleep(20);
                                }
                            } catch (InterruptedException error) {
                                Thread.currentThread().interrupt();
                            } catch (Exception error) {
                                try {
                                    Thread.sleep(20);
                                } catch (InterruptedException interrupted) {
                                    Thread.currentThread().interrupt();
                                }
                            }
                        }
                    });
        }

        @Override
        public void close() {
            transport.close();
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }

    private static final class TextMessageWorkerHandle
            implements RunningWorker {

        private final JavaWorker worker;

        private TextMessageWorkerHandle(JavaWorker worker) {
            this.worker = worker;
            try {
                worker.start();
            } catch (RuntimeException | Error failure) {
                worker.close();
                throw failure;
            }
        }

        @Override
        public void close() {
            worker.close();
        }
    }
}
