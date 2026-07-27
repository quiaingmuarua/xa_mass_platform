package com.xa.mass.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import io.lettuce.core.RedisClient;
import io.lettuce.core.api.StatefulRedisConnection;
import io.lettuce.core.codec.StringCodec;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.WebSocket;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class RuntimeApiPythonIntegrationTest {

    private static final String KERNEL_URL =
            System.getenv("KERNEL_COMMAND_INTEGRATION_URL");
    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private static final String REDIS_PREFIX =
            System.getenv().getOrDefault(
                    "KERNEL_DESIGN_REDIS_PREFIX",
                    "default"
            );
    private static final String ENDPOINT_MANAGER_ID =
            "java-websocket-integration";
    private static final String PHONE_RESULT = """
            {"countryCallingCode":1,"e164":"+14155552671",\
            "isPossible":true,"isValid":true,"regionCode":"US"}\
            """;

    @LocalServerPort
    private int port;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private RedisClient redisClient;

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
                "xa.mass.worker-delivery.redis-url",
                () -> configured(
                        REDIS_URL,
                        "redis://127.0.0.1:6379/15"
                )
        );
        registry.add(
                "xa.mass.worker-delivery.redis-prefix",
                () -> REDIS_PREFIX
        );
        registry.add(
                "xa.mass.worker-delivery.websocket.enabled",
                () -> "true"
        );
        registry.add(
                "xa.mass.worker-delivery.websocket.endpoint-manager-id",
                () -> ENDPOINT_MANAGER_ID
        );
        registry.add(
                "xa.mass.worker-delivery.websocket.pump-interval",
                () -> "20ms"
        );
    }

    @Test
    void taskDrivenClosesThroughTheJavaWorkerDeliveryGateway()
            throws Exception {
        runWorkerDeliveryClosure("TASK_DRIVEN");
    }

    @Test
    void itemDrivenClosesThroughTheJavaWorkerDeliveryGateway()
            throws Exception {
        runWorkerDeliveryClosure("ITEM_DRIVEN");
    }

    @Test
    void missingWebSocketSessionProducesTrustedRecoveryEvidence()
            throws Exception {
        requireExternalRuntime();
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "missing-session-" + suffix;
        String workerId = "worker-" + suffix;
        String taskId = "task-" + suffix;

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId,
                """
                        {"eventCodes":["telecom.phone.inspect"],\
                        "itemAllocationFields":[]}\
                        """
        ).statusCode()).isEqualTo(200);
        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId
                        + "/workers/" + workerId,
                """
                        {"endpointManagerId":"%s","attributes":{},\
                        "dynamicAttributeNames":[]}\
                        """.formatted(ENDPOINT_MANAGER_ID)
        ).statusCode()).isEqualTo(200);
        assertThat(send(
                "POST",
                "/api/v1/tasks",
                taskRequest(taskId, workerGroupId, "TASK_DRIVEN")
        ).statusCode()).isEqualTo(201);
        assertThat(send(
                "POST",
                "/api/v1/tasks/" + taskId + "/approve",
                null
        ).statusCode()).isEqualTo(200);
        appendItem(taskId, "message-" + suffix, workerId, false);

        awaitNegativeWorkerScore(workerGroupId, workerId);

        assertThat(send(
                "POST",
                "/api/v1/tasks/" + taskId + "/close",
                null
        ).statusCode()).isEqualTo(200);
    }

    private void runWorkerDeliveryClosure(String taskType) throws Exception {
        requireExternalRuntime();
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "phone-tools-" + suffix;
        String workerId = "worker-" + suffix;
        String taskId = "task-" + suffix;
        String firstMessageId = "message-1-" + suffix;
        String secondMessageId = "message-2-" + suffix;

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId,
                """
                        {
                          "eventCodes": ["telecom.phone.inspect"],
                          "itemAllocationFields": %s
                        }
                        """.formatted(
                        "ITEM_DRIVEN".equals(taskType)
                                ? "[\"workerId\"]"
                                : "[]"
                )
        ).statusCode()).isEqualTo(200);

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId
                        + "/workers/" + workerId,
                """
                        {
                          "endpointManagerId": "%s",
                          "attributes": {"runtime": "java"},
                          "dynamicAttributeNames": []
                        }
                        """.formatted(ENDPOINT_MANAGER_ID)
        ).statusCode()).isEqualTo(200);

        WorkerSocket worker = connectWorker(workerId);
        try {
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

            appendItem(
                    taskId,
                    firstMessageId,
                    workerId,
                    "ITEM_DRIVEN".equals(taskType)
            );
            JsonNode firstCommand = worker.awaitCommand();
            worker.submitSuccess(firstCommand);
            awaitStoredResult(taskId, firstMessageId);

            appendItem(
                    taskId,
                    secondMessageId,
                    workerId,
                    "ITEM_DRIVEN".equals(taskType)
            );
            JsonNode secondCommand = worker.awaitCommand();
            assertThat(secondCommand.get("commandId").textValue())
                    .isNotEqualTo(firstCommand.get("commandId").textValue());
            worker.submitSuccess(secondCommand);
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
                            "eventCode": "telecom.phone.inspect",
                            "createdAtMillis": %d,
                            "payload": {"phoneNumber": "+14155552671"}%s
                          }]
                        }
                        """.formatted(
                        messageId,
                        System.currentTimeMillis() - 1_000,
                        allocationRule
                )
        );
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.body()).contains("\"status\":\"appended\"");
    }

    private WorkerSocket connectWorker(String workerId) {
        WorkerSocket listener = new WorkerSocket(workerId);
        WebSocket socket = HttpClient.newHttpClient()
                .newWebSocketBuilder()
                .buildAsync(
                        URI.create(
                                "ws://127.0.0.1:" + port
                                        + "/api/v1/worker-delivery/"
                                        + "websocket/workers/" + workerId
                        ),
                        listener
                )
                .join();
        listener.attach(socket);
        return listener;
    }

    private void awaitStoredResult(
            String taskId,
            String messageId
    ) throws Exception {
        try (StatefulRedisConnection<String, String> connection =
                     redisClient.connect(StringCodec.UTF8)) {
            var redis = connection.sync();
            String resultKey = "tr:" + REDIS_PREFIX + ":task:"
                    + taskId + ":results";
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline) {
                String result = redis.hget(resultKey, messageId);
                if (PHONE_RESULT.equals(result)) {
                    return;
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError("TaskItem success result was not stored");
    }

    private void awaitNegativeWorkerScore(
            String workerGroupId,
            String workerId
    ) throws Exception {
        try (StatefulRedisConnection<String, String> connection =
                     redisClient.connect(StringCodec.UTF8)) {
            var redis = connection.sync();
            String scoreKey = "wr:" + REDIS_PREFIX + ":score:"
                    + workerGroupId;
            long deadline = System.nanoTime()
                    + Duration.ofSeconds(8).toNanos();
            while (System.nanoTime() < deadline) {
                Double score = redis.zscore(scoreKey, workerId);
                if (score != null && score < 0) {
                    return;
                }
                Thread.sleep(20);
            }
        }
        throw new AssertionError(
                "Adapter rejection did not move Worker to recovery"
        );
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

    private final class WorkerSocket implements WebSocket.Listener {

        private final String workerId;
        private final BlockingQueue<String> commands =
                new LinkedBlockingQueue<>();
        private final StringBuilder fragmented = new StringBuilder();
        private WebSocket socket;

        private WorkerSocket(String workerId) {
            this.workerId = workerId;
        }

        private void attach(WebSocket value) {
            socket = value;
        }

        @Override
        public void onOpen(WebSocket webSocket) {
            webSocket.request(1);
        }

        @Override
        public CompletionStage<?> onText(
                WebSocket webSocket,
                CharSequence data,
                boolean last
        ) {
            fragmented.append(data);
            if (last) {
                commands.add(fragmented.toString());
                fragmented.setLength(0);
            }
            webSocket.request(1);
            return null;
        }

        private JsonNode awaitCommand() throws Exception {
            String encoded = commands.poll(8, TimeUnit.SECONDS);
            if (encoded == null) {
                throw new AssertionError("Worker command was not produced");
            }
            JsonNode command = objectMapper.readTree(encoded);
            assertThat(command.get("messageType").textValue())
                    .isEqualTo("TASK_ITEM");
            return command;
        }

        private void submitSuccess(JsonNode command) throws Exception {
            JsonNode seed = objectMapper.readTree(
                    command.get("opaqueItem").textValue()
            );
            assertThat(seed.get("workerId").textValue())
                    .isEqualTo(workerId);
            var result = objectMapper.createObjectNode();
            result.put(
                    "commandId",
                    command.get("commandId").textValue()
            );
            result.put(
                    "opaqueResultContext",
                    seed.get("opaqueResultContext").textValue()
            );
            result.put("outcomeCode", "200");
            result.put("opaqueResultPayload", PHONE_RESULT);
            socket.sendText(
                    objectMapper.writeValueAsString(result),
                    true
            ).join();
        }

        private void close() {
            if (socket != null) {
                socket.sendClose(
                        WebSocket.NORMAL_CLOSURE,
                        "complete"
                ).join();
            }
        }
    }
}
