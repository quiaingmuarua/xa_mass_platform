package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
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
    void usesCommandCredentialForSealAndApprove() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<RecordedRequest> requests = new ArrayList<>();
        try (RecordingServer server = RecordingServer.start(requests)) {
            ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                    "--register-only",
                    "--base-url", server.baseUrl(),
                    "--task-api-key", "submitter-key",
                    "--task-command-api-key", "command-key"
            });
            TaskScenarioSeeder seeder = new TaskScenarioSeeder(options, objectMapper,
                    new ScenarioClientFactory(server.baseUrl(), HttpClient.newHttpClient(), objectMapper));

            seeder.seed(List.of(new TaskScenarioSpec(
                    true,
                    null,
                    1,
                    Map.of(
                            "project", "demoApp",
                            "userId", "sample",
                            "eventCode", "demo.dispatch",
                            "items", List.of(Map.of("id", "item-1"))
                    )
            )));

            assertEquals("submitter-key", requests.get(0).headers().get("X-mass-api-key"));
            assertEquals("/api/v1/tasks", requests.get(0).path());
            assertEquals("submitter-key", requests.get(1).headers().get("X-mass-api-key"));
            assertEquals("/api/v1/tasks/task-001/items", requests.get(1).path());
            assertEquals("command-key", requests.get(2).headers().get("X-mass-api-key"));
            assertEquals("/api/v1/tasks/task-001/commands", requests.get(2).path());
            assertEquals("command-key", requests.get(3).headers().get("X-mass-api-key"));
            assertEquals("/api/v1/tasks/task-001/commands", requests.get(3).path());
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
            String responseBody = path.equals("/api/v1/tasks")
                    ? "{\"code\":0,\"data\":{\"taskId\":\"task-001\"}}"
                    : "{\"code\":0,\"data\":{}}";
            byte[] response = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
