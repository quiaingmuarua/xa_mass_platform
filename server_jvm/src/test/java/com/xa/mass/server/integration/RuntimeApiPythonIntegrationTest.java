package com.xa.mass.server.integration;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Tag("integration")
class RuntimeApiPythonIntegrationTest {

    private static final String KERNEL_URL =
            System.getenv("KERNEL_COMMAND_INTEGRATION_URL");

    @LocalServerPort
    private int port;

    @DynamicPropertySource
    static void kernelProperties(DynamicPropertyRegistry registry) {
        registry.add(
                "xa.mass.kernel.base-url",
                () -> KERNEL_URL == null
                        ? "http://127.0.0.1:18080"
                        : KERNEL_URL
        );
    }

    @Test
    void publicRuntimeCommandsReachPythonAndPersistInRedis() throws Exception {
        Assumptions.assumeTrue(
                KERNEL_URL != null && !KERNEL_URL.isBlank(),
                "KERNEL_COMMAND_INTEGRATION_URL is not configured"
        );
        String suffix = UUID.randomUUID().toString();
        String workerGroupId = "phone-tools-" + suffix;
        String workerId = "worker-" + suffix;
        String taskId = "task-" + suffix;
        String messageId = "message-" + suffix;

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId,
                """
                        {
                          "eventCodes": ["telecom.phone.inspect"],
                          "itemAllocationFields": ["workerId"]
                        }
                        """
        ).statusCode()).isEqualTo(200);

        assertThat(send(
                "PUT",
                "/api/v1/worker-groups/" + workerGroupId + "/workers/" + workerId,
                """
                        {
                          "endpointManagerId": "integration-endpoint",
                          "attributes": {"runtime": "java"},
                          "dynamicAttributeNames": []
                        }
                        """
        ).statusCode()).isEqualTo(200);

        String createBody = """
                {
                  "taskId": "%s",
                  "workerGroupId": "%s",
                  "taskType": "TASK_DRIVEN",
                  "allocationRule": {},
                  "config": {
                    "priority": "0",
                    "maximumCandidateWorkers": "1",
                    "maxRetryTimes": "3"
                  },
                  "emptyCloseAtMillis": 0
                }
                """.formatted(taskId, workerGroupId);
        HttpResponse<String> created =
                send("POST", "/api/v1/tasks", createBody);
        assertThat(created.statusCode()).isEqualTo(201);
        assertThat(created.body()).contains("\"status\":\"created\"");

        HttpResponse<String> duplicate =
                send("POST", "/api/v1/tasks", createBody);
        assertThat(duplicate.statusCode()).isEqualTo(409);
        assertThat(duplicate.body()).contains("\"status\":\"conflict\"");

        HttpResponse<String> approved =
                send("POST", "/api/v1/tasks/" + taskId + "/approve", null);
        assertThat(approved.statusCode()).isEqualTo(200);
        assertThat(approved.body()).contains("\"status\":\"approved\"");

        HttpResponse<String> appended = send(
                "POST",
                "/api/v1/tasks/" + taskId + "/items",
                """
                        {
                          "items": [{
                            "messageId": "%s",
                            "eventCode": "telecom.phone.inspect",
                            "createdAtMillis": %d,
                            "payload": {"phoneNumber": "+14155552671"}
                          }]
                        }
                        """.formatted(messageId, System.currentTimeMillis())
        );
        assertThat(appended.statusCode()).isEqualTo(200);
        assertThat(appended.body())
                .contains("\"results\"")
                .contains("\"status\":\"appended\"");
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
        return HttpClient.newHttpClient().send(
                request.build(),
                HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)
        );
    }
}
