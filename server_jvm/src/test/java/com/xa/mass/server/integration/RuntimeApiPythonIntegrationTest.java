package com.xa.mass.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.kernel.score.TaskItemScoreBandCore;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.worker.runtime.PreparedWorker;
import com.xa.mass.worker.runtime.WorkerLoop;
import com.xa.mass.worker.runtime.WorkerPreparation;
import com.xa.mass.worker.runtime.WorkerRetryPolicy;
import com.xa.mass.transport.client.jdk.JdkLineSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpTextWebSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerPointClient;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import io.lettuce.core.RedisClient;
import io.lettuce.core.codec.StringCodec;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@ActiveProfiles("test")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Tag("integration")
class RuntimeApiPythonIntegrationTest {

    private static final String KERNEL_URL =
            System.getenv("KERNEL_COMMAND_INTEGRATION_URL");
    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private static final int SERVER_PORT = availablePort();
    private static final int[] ACTIVE_ADAPTER_PORTS =
            availablePorts(2);
    private static final String WEBSOCKET_ENDPOINT_MANAGER_ID =
            "java-websocket-integration";
    private static final String SOCKET_ENDPOINT_MANAGER_ID =
            "java-socket-integration";
    private static final String TEST_EVENT_CODE =
            "test.integration.observe";
    private static final String TEST_RESULT = "{\"observed\":\"input\"}";
    private static final JsonMapper JSON = JsonMapper.builder().build();

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void integrationProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "xa.mass.kernel.base-url",
                () -> configured(
                        KERNEL_URL,
                        "http://127.0.0.1:18080"
                )
        );
        registry.add(
                "xa.mass.kernel-redis.redis-url",
                () -> configured(
                        REDIS_URL,
                        "redis://127.0.0.1:6379/15"
                )
        );
        registry.add(
                "xa.mass.kernel-redis.redis-prefix",
                () -> System.getenv().getOrDefault(
                        "KERNEL_DESIGN_REDIS_PREFIX",
                        "default"
                )
        );
        registry.add(
                "server.port",
                () -> Integer.toString(SERVER_PORT)
        );
        registry.add(
                "xa.mass.worker-delivery.adapter.gateway.base-url",
                () -> "http://127.0.0.1:" + SERVER_PORT
        );
        registry.add(
                "xa.mass.worker-delivery.adapter.gateway.request-timeout",
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
    void taskDrivenClosesThroughTheJavaPollingWorker()
            throws Exception {
        runWorkerDeliveryClosure(
                "TASK_DRIVEN",
                TransportProfile.POLLING
        );
    }

    @Test
    void itemDrivenClosesThroughWebSocketAndSocketAdapters()
            throws Exception {
        runWorkerDeliveryClosure(
                "ITEM_DRIVEN",
                TransportProfile.WEBSOCKET
        );
        runWorkerDeliveryClosure(
                "ITEM_DRIVEN",
                TransportProfile.SOCKET
        );
    }

    @Test
    void targetedSchedulingUsesWorkerIdThenMatchesPropertyProjections()
            throws Exception {
        requireExternalRuntime();
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "indexed-tools-" + suffix;
        String clientWorkerKey = "indexed-worker-" + suffix;
        String taskId = "indexed-task-" + suffix;

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
                Map.of("region", "snapshot-only")
        );
        String workerId = boundWorker.workerId();

        RunningWorker worker = startWorker(
                workerId,
                boundWorker.endpointUri(),
                TransportProfile.WEBSOCKET
        );
        try {
            awaitWorkerRegistered(workerGroupId, workerId);
            awaitIndexedPropertiesUpdate(
                    workerGroupId,
                    workerId,
                    "{\"updates\":{"
                            + "\"index.worker.region\":\"cn-east\","
                            + "\"index.platform.pool\":\"batch\"}}"
            );
            assertThat(send(
                    "POST",
                    "/api/v1/tasks",
                    taskRequest(taskId, workerGroupId, "ITEM_DRIVEN")
            ).statusCode()).isEqualTo(201);
            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/approve",
                    null
            ).statusCode()).isEqualTo(200);

            String indexedRule = "{"
                    + "\"workerId\":{\"$eq\":\"" + workerId + "\"},"
                    + "\"index.worker.region\":{\"$eq\":\"cn-east\"},"
                    + "\"index.platform.pool\":{\"$in\":[\"batch\"]}"
                    + "}";
            callItemWithAllocationRule(
                    taskId,
                    "indexed-message-1-" + suffix,
                    indexedRule
            );
            callItemWithAllocationRule(
                    taskId,
                    "indexed-message-2-" + suffix,
                    indexedRule
            );

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
    void pythonControlApiExposesOnlyTaskCommands() throws Exception {
        requireExternalRuntime();
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
            String taskType,
            TransportProfile transportProfile
    ) throws Exception {
        requireExternalRuntime();
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
                workerId,
                boundWorker.endpointUri(),
                transportProfile
        );
        try {
            awaitWorkerRegistered(workerGroupId, workerId);
            assertThat(send(
                    "POST",
                    "/api/v1/tasks",
                    taskRequest(taskId, workerGroupId, taskType)
            ).statusCode()).isEqualTo(201);
            assertThat(send(
                    "POST",
                    "/api/v1/tasks/" + taskId + "/approve",
                    null
            ).statusCode()).isEqualTo(200);

            callItem(
                    taskId,
                    firstMessageId,
                    workerId,
                    "ITEM_DRIVEN".equals(taskType)
            );

            appendItem(
                    taskId,
                    secondMessageId,
                    workerId,
                    "ITEM_DRIVEN".equals(taskType)
            );
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

    private void callItem(
            String taskId,
            String messageId,
            String workerId,
            boolean itemDriven
    ) throws Exception {
        String allocationRule = itemDriven
                ? "{\"workerId\":{\"$eq\":\"" + workerId + "\"}}"
                : null;
        callItemWithAllocationRule(taskId, messageId, allocationRule);
    }

    private void callItemWithAllocationRule(
            String taskId,
            String messageId,
            String allocationRule
    ) throws Exception {
        String encodedAllocationRule = allocationRule == null
                ? ""
                : ",\"allocationRule\":" + allocationRule;
        HttpResponse<String> response = send(
                "POST",
                "/api/v1/tasks/" + taskId + "/items:call",
                """
                        {
                          "item": {
                            "messageId": "%s",
                            "eventCode": "%s",
                            "createdAtMillis": %d,
                            "payload": {"value": "input"}%s
                          },
                          "waitTimeoutMillis": 8000
                        }
                        """.formatted(
                        messageId,
                        TEST_EVENT_CODE,
                        System.currentTimeMillis() - 1_000,
                        encodedAllocationRule
                )
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(JSON.readTree(response.body()).get("status").stringValue())
                .isEqualTo("succeeded");
        assertThat(JSON.readTree(response.body())
                .get("opaqueResultPayload")
                .stringValue()).isEqualTo(TEST_RESULT);
        assertStoredItemAndFinalSuccess(taskId, messageId);
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
            boolean itemDriven
    ) throws Exception {
        String allocationRule = itemDriven
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
            String workerId,
            URI serverUrl,
            TransportProfile transportProfile
    ) throws Exception {
        List<WorkerEventDefinition<?>> definitions =
                List.of(
                        WorkerEventDefinition.of(
                                "TASK",
                                TEST_EVENT_CODE,
                                WorkerEventParameterResolvers.jsonMap(),
                                payload ->
                                com.xa.mass.workerdelivery.json.Jsons.toJson(
                                        Map.of(
                                                "observed",
                                                payload.get("value")
                                        )
                                )
                        )
                );
        return switch (transportProfile) {
            case WEBSOCKET -> new TextMessageWorkerHandle(
                    new WorkerLoop(
                            fixedPreparation(workerId, serverUrl),
                            definitions,
                            endpoint -> new OkHttpTextWebSocketClient(
                                    endpoint,
                                    Duration.ofSeconds(2),
                                    connectionPolicy()
                            ),
                            workerRetryPolicy()
                    )
            );
            case SOCKET -> new TextMessageWorkerHandle(
                    new WorkerLoop(
                            fixedPreparation(workerId, serverUrl),
                            definitions,
                            endpoint -> new JdkLineSocketClient(
                                    endpoint,
                                    Duration.ofSeconds(2),
                                    connectionPolicy()
                            ),
                            workerRetryPolicy()
                    )
            );
            case POLLING -> new PollingWorkerHandle(
                    new PollingWorkerTransport(
                            new OkHttpWorkerPointClient(
                                    serverUrl,
                                    Duration.ofSeconds(2)
                            ),
                            workerId,
                            definitions
                    )
            );
        };
    }

    private static TextMessageReconnectPolicy connectionPolicy() {
        return TextMessageReconnectPolicy.of(
                20,
                Duration.ofMillis(20),
                Duration.ofSeconds(1)
        );
    }

    private static WorkerRetryPolicy workerRetryPolicy() {
        return WorkerRetryPolicy.of(
                1,
                Duration.ofMillis(20),
                connectionPolicy()
        );
    }

    private static WorkerPreparation fixedPreparation(
            String workerId,
            URI endpointUri
    ) {
        return new WorkerPreparation() {
            @Override
            public PreparedWorker prepare() {
                return new PreparedWorker(workerId, endpointUri);
            }

            @Override
            public void close() {
            }
        };
    }

    private BoundWorker registerAndBindWorker(
            String workerGroupId,
            String clientWorkerKey,
            TransportProfile profile,
            Map<String, Object> workerProperties
    ) throws Exception {
        try (var client = new OkHttpWorkerControlClient(
                URI.create("http://127.0.0.1:" + port)
        )) {
            Map<String, Object> completeProperties =
                    new LinkedHashMap<>(workerProperties);
            completeProperties.put(
                    "clientWorkerKey",
                    clientWorkerKey
            );
            String workerId = client.register(
                    workerGroupId,
                    completeProperties,
                    Duration.ofSeconds(2)
            );
            URI endpointUri = client.bind(
                    workerGroupId,
                    workerId,
                    switch (profile) {
                        case POLLING -> WorkerTransportType.POLLING;
                        case WEBSOCKET -> WorkerTransportType.WEBSOCKET;
                        case SOCKET -> WorkerTransportType.SOCKET;
                    },
                    completeProperties,
                    Duration.ofSeconds(2)
            );
            return new BoundWorker(workerId, endpointUri);
        }
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

    private void awaitIndexedPropertiesUpdate(
            String workerGroupId,
            String workerId,
            String body
    ) throws Exception {
        long deadline = System.nanoTime()
                + Duration.ofSeconds(3).toNanos();
        while (System.nanoTime() < deadline) {
            HttpResponse<String> response = send(
                    "PATCH",
                    "/api/v1/worker-groups/" + workerGroupId
                            + "/workers/" + workerId
                            + "/indexed-properties",
                    body
            );
            if (response.statusCode() == 200
                    && allIndexUpdatesAccepted(response.body())) {
                return;
            }
            Thread.sleep(20);
        }
        throw new AssertionError("Worker indexes were not updated");
    }

    private static boolean allIndexUpdatesAccepted(String responseBody)
            throws Exception {
        var results = JSON.readTree(responseBody).get("results");
        if (results == null || !results.isObject() || results.isEmpty()) {
            return false;
        }
        for (var result : results) {
            var status = result.get("status");
            if (status == null
                    || !(status.stringValue().equals("ok")
                    || status.stringValue().equals("noop"))) {
                return false;
            }
        }
        return true;
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
            String taskType
    ) {
        String allocationRule = "TASK_DRIVEN".equals(taskType)
                ? "\"allocationRule\":{},"
                : "";
        return """
                {
                  "taskId": "%s",
                  "workerGroupId": "%s",
                  "taskType": "%s",
                  %s
                  "config": {
                    "priority": "0",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3"
                  },
                  "emptyCloseAtMillis": %d
                }
                """.formatted(
                taskId,
                workerGroupId,
                taskType,
                allocationRule,
                System.currentTimeMillis() + 60_000
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
                .uri(URI.create(KERNEL_URL + path))
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

    private static void requireExternalRuntime() {
        Assumptions.assumeTrue(
                KERNEL_URL != null
                        && !KERNEL_URL.isBlank()
                        && REDIS_URL != null
                        && !REDIS_URL.isBlank(),
                "Kernel Runtime Server and Redis are not configured"
        );
    }

    private static String configured(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
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
        registry.add(prefix + ".command-loop-interval", () -> "20ms");
        registry.add(prefix + ".command-consume-limit", () -> "100");
        registry.add(prefix + ".command-queue-capacity", () -> "1000");
        registry.add(prefix + ".result-submit-interval", () -> "20ms");
        registry.add(prefix + ".result-queue-capacity", () -> "1000");
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
        registry.add(prefix + ".command-loop-interval", () -> "20ms");
        registry.add(prefix + ".command-consume-limit", () -> "100");
        registry.add(prefix + ".command-queue-capacity", () -> "1000");
        registry.add(prefix + ".result-submit-interval", () -> "20ms");
        registry.add(prefix + ".result-queue-capacity", () -> "1000");
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

        private final WorkerLoop transport;

        private TextMessageWorkerHandle(
                WorkerLoop transport
        ) throws Exception {
            this.transport = transport;
            transport.start();
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(2).toNanos();
            while (!transport.isConnected()
                    && System.nanoTime() < deadline) {
                Thread.sleep(10);
            }
            if (!transport.isConnected()) {
                transport.close();
                throw new AssertionError(
                        "Text-message Worker did not connect to its Adapter"
                );
            }
        }

        @Override
        public void close() {
            transport.close();
        }
    }
}
