package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TaskScenarioSeederTest {
    @Test
    void chunksItemsByConfiguredSize() {
        List<List<Object>> chunks = TaskScenarioSeeder.chunks(List.of(1, 2, 3, 4, 5), 2);

        assertEquals(List.of(List.of(1, 2), List.of(3, 4), List.of(5)), chunks);
    }

    @Test
    void createsTaskAndAppendsItemsOnly() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<RecordedRequest> requests = new ArrayList<>();
        try (RecordingServer server = RecordingServer.start(requests)) {
            ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                    "--base-url", server.baseUrl(),
                    "--task-api-key", "task-api-key"
            });
            TaskScenarioSeeder seeder = new TaskScenarioSeeder(options, objectMapper,
                    new ScenarioClientFactory(server.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(30), objectMapper));

            List<TaskScenarioSeeder.SeededTask> seededTasks = seeder.seed(List.of(new TaskScenarioSpec(
                    null,
                    1,
                    Map.of(
                            "project", "demoApp",
                            "userId", "sample",
                            "eventCode", "demo.dispatch",
                            "items", List.of(Map.of("id", "item-1"))
                    )
            )));

            assertEquals("task-api-key", requests.get(0).headers().get("X-mass-api-key"));
            assertEquals("/api/v1/tasks", requests.get(0).path());
            assertEquals("task-api-key", requests.get(1).headers().get("X-mass-api-key"));
            assertEquals("/api/v1/tasks/task-001/items", requests.get(1).path());
            Map<?, ?> createBody = objectMapper.readValue(requests.get(0).body(), Map.class);
            assertEquals(false, createBody.containsKey("eventCode"));
            Map<?, ?> appendBody = objectMapper.readValue(requests.get(1).body(), Map.class);
            assertEquals("demo.dispatch", appendBody.get("eventCode"));
            assertEquals(2, requests.size());
            assertEquals(1, seededTasks.size());
            assertEquals("task-001", seededTasks.getFirst().taskId());
        }
    }

    @Test
    void doesNotCallTaskCommands() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<RecordedRequest> requests = new ArrayList<>();
        try (RecordingServer server = RecordingServer.start(requests)) {
            ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                    "--base-url", server.baseUrl(),
                    "--task-api-key", "task-api-key"
            });
            TaskScenarioSeeder seeder = new TaskScenarioSeeder(options, objectMapper,
                    new ScenarioClientFactory(server.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(30), objectMapper));

            List<TaskScenarioSeeder.SeededTask> seededTasks = seeder.seed(List.of(new TaskScenarioSpec(
                    null,
                    1,
                    Map.of(
                            "project", "deviceProbe",
                            "userId", "sample",
                            "eventCode", "probe.phone.metadata",
                            "sharedConfig", Map.of("workerGroupId", "phone-device-probe"),
                            "items", List.of(Map.of("id", "item-1"))
                    )
            )));

            long commandCount = requests.stream()
                    .filter(request -> request.path().equals("/api/v1/tasks/task-001/commands"))
                    .count();
            assertEquals(0, commandCount);
            assertEquals(1, seededTasks.size());
        }
    }

    @Test
    void preservesCreatedTaskIdentity() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<RecordedRequest> requests = new ArrayList<>();
        try (RecordingServer server = RecordingServer.start(requests)) {
            ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                    "--base-url", server.baseUrl(),
                    "--task-api-key", "task-api-key"
            });
            TaskScenarioSeeder seeder = new TaskScenarioSeeder(options, objectMapper,
                    new ScenarioClientFactory(server.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(30), objectMapper));

            List<TaskScenarioSeeder.SeededTask> seededTasks = seeder.seed(List.of(new TaskScenarioSpec(
                    null,
                    1,
                    Map.of(
                            "project", "deviceProbe",
                            "userId", "sample",
                            "eventCode", "probe.phone.metadata",
                            "sharedConfig", Map.of("workerGroupId", "phone-device-probe"),
                            "items", List.of(Map.of("id", "item-1"))
                    )
            )));

            long commandCount = requests.stream()
                    .filter(request -> request.path().equals("/api/v1/tasks/task-001/commands"))
                    .count();
            assertEquals(0, commandCount);
            assertEquals("task-001", seededTasks.getFirst().taskId());
        }
    }

    @Test
    void waitsForVisibleSuccessThroughSdkResultClient() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<RecordedRequest> requests = new ArrayList<>();
        try (RecordingServer server = RecordingServer.start(requests)) {
            ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                    "--base-url", server.baseUrl(),
                    "--task-api-key", "task-api-key",
                    "--wait-visible-success",
                    "--result-wait-timeout-seconds", "1"
            });
            ScenarioClientFactory clientFactory = new ScenarioClientFactory(
                    server.baseUrl(),
                    Duration.ofSeconds(5),
                    Duration.ofSeconds(30),
                    objectMapper);
            TaskScenarioSeeder seeder = new TaskScenarioSeeder(options, objectMapper, clientFactory);

            List<TaskScenarioSeeder.SeededTask> seededTasks = seeder.seed(List.of(new TaskScenarioSpec(
                    null,
                    1,
                    Map.of(
                            "project", "demoApp",
                            "userId", "sample",
                            "eventCode", "demo.dispatch",
                            "items", List.of(Map.of("id", "item-1"))
                    )
            )));
            new ScenarioTaskResultVerifier(clientFactory, options).waitForVisibleSuccess(seededTasks);

            assertEquals("/api/v1/tasks/task-001/results", requests.get(2).path());
            assertEquals(3, requests.size());
        }
    }

    private record RecordedRequest(String path, Map<String, String> headers, String body) {
    }

    private static final class RecordingServer implements AutoCloseable {
        private final HttpServer server;

        private RecordingServer(HttpServer server) {
            this.server = server;
        }

        static RecordingServer start(List<RecordedRequest> requests) throws IOException {
            HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", exchange -> handle(exchange, requests));
            server.start();
            return new RecordingServer(server);
        }

        String baseUrl() {
            return "http://127.0.0.1:" + server.getAddress().getPort();
        }

        @Override
        public void close() {
            server.stop(0);
        }

        private static void handle(HttpExchange exchange, List<RecordedRequest> requests) throws IOException {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            String path = exchange.getRequestURI().getPath();
            requests.add(new RecordedRequest(
                    path,
                    Map.of("X-mass-api-key", exchange.getRequestHeaders().getFirst("X-Mass-Api-Key")),
                    body
            ));
            String responseBody;
            if (path.equals("/api/v1/tasks")) {
                responseBody = "{\"code\":0,\"data\":{\"taskId\":\"task-001\"}}";
            } else if (path.equals("/api/v1/tasks/task-001/results")) {
                responseBody = """
                        {"code":0,"data":{
                          "mode":"WINDOW",
                          "taskId":"task-001",
                          "taskTerminal":false,
                          "archiveReady":false,
                          "items":[{"seq":1,"messageId":"message-1","eventCode":"demo.dispatch","status":"SUCCESS"}],
                          "nextAfterSeq":1,
                          "hasMore":false
                        }}
                        """;
            } else {
                responseBody = "{\"code\":0,\"data\":{}}";
            }
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
