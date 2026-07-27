package com.xa.mass.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import com.xa.mass.worker.execution.PhoneInspectHandler;
import com.xa.mass.worker.execution.WorkerCommandProcessor;
import com.xa.mass.worker.transport.polling.PollingWorkerTransport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import java.net.URI;
import java.net.ServerSocket;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import tools.jackson.databind.json.JsonMapper;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@Tag("integration")
class RuntimeApiPythonIntegrationTest {

    private static final String KERNEL_URL =
            System.getenv("KERNEL_COMMAND_INTEGRATION_URL");
    private static final String REDIS_URL =
            System.getenv("KERNEL_DESIGN_REDIS_URL");
    private static final int SERVER_PORT = availablePort();
    private static final String PHONE_RESULT = """
            {"countryCallingCode":1,"e164":"+14155552671",\
            "isPossible":true,"isValid":true,"regionCode":"US"}\
            """;
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
    }

    @Test
    void taskDrivenClosesThroughTheJavaPollingWorker()
            throws Exception {
        runWorkerDeliveryClosure("TASK_DRIVEN");
    }

    @Test
    void itemDrivenClosesThroughTheJavaPollingWorker()
            throws Exception {
        runWorkerDeliveryClosure("ITEM_DRIVEN");
    }

    @Test
    void pythonControlApiDoesNotExposeTaskItemAppend() throws Exception {
        requireExternalRuntime();
        HttpRequest request = HttpRequest.newBuilder()
                .uri(URI.create(KERNEL_URL + "/tasks/missing/items"))
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(
                        "{\"items\":[]}",
                        StandardCharsets.UTF_8
                ))
                .build();

        HttpResponse<String> response = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .build()
                .send(
                        request,
                        HttpResponse.BodyHandlers.ofString(
                                StandardCharsets.UTF_8
                        )
                );

        assertThat(response.statusCode()).isEqualTo(404);
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
                          "endpointManagerId": "system-polling",
                          "attributes": {"runtime": "java"},
                          "dynamicAttributeNames": []
                        }
                        """
        ).statusCode()).isEqualTo(200);

        RunningWorker worker = startWorker(
                workerId,
                URI.create("http://127.0.0.1:" + port)
        );
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
            awaitStoredResult(taskId, firstMessageId);

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

    private RunningWorker startWorker(
            String workerId,
            URI serverUrl
    ) {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        WorkerCommandProcessor processor = new WorkerCommandProcessor(
                workerId,
                codec,
                Map.of(
                        PhoneInspectHandler.EVENT_CODE,
                        new PhoneInspectHandler()
                )
        );
        return new PollingWorkerHandle(new PollingWorkerTransport(
                serverUrl,
                WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID,
                workerId,
                Duration.ofSeconds(2),
                codec,
                processor
        ));
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
                if (PHONE_RESULT.equals(result)) {
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

    private interface RunningWorker extends AutoCloseable {

        @Override
        void close();
    }

    private static final class PollingWorkerHandle
            implements RunningWorker {

        private final Thread thread;

        private PollingWorkerHandle(PollingWorkerTransport transport) {
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
            thread.interrupt();
            try {
                thread.join(1_000);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
        }
    }
}
