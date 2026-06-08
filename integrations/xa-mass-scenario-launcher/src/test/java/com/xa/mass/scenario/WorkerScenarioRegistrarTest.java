package com.xa.mass.scenario;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import com.xa.mass.client.worker.WorkerEventBindingSpec;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class WorkerScenarioRegistrarTest {
    @Test
    void registersTopologyOnceAndRegistersEachWorker() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<RecordedRequest> requests = new ArrayList<>();
        try (RecordingServer server = RecordingServer.start(requests)) {
            ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                    "--base-url", server.baseUrl(),
                    "--worker-api-key", "override-worker-key"
            });
            WorkerScenarioRegistrar registrar = new WorkerScenarioRegistrar(options,
                    new ScenarioClientFactory(server.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(30), objectMapper));

            registrar.register(List.of(
                    worker("worker-001"),
                    worker("worker-002")
            ));

            assertEquals(1, registrar.declaredWorkerGroupCount());
            assertEquals(1, registrar.registeredAdapterNodeCount());
            assertEquals(1, registrar.boundAdapterNodeGroupCount());
            assertEquals(1, countPath(requests, "/worker-api/v1/worker-groups"));
            assertEquals(1, countPath(requests, "/worker-api/v1/adapter-nodes"));
            assertEquals(1, countPath(requests, "/worker-api/v1/node-group-bindings"));
            assertEquals(2, countPath(requests, "/worker-api/v1/workers"));
            assertTrue(requests.stream().allMatch(request ->
                    "override-worker-key".equals(request.headers().get("X-mass-api-key"))));
        }
    }

    @Test
    void launchModeRegistrationDoesNotMarkApiOnlineWithoutSession() throws Exception {
        ObjectMapper objectMapper = new ObjectMapper().findAndRegisterModules();
        List<RecordedRequest> requests = new ArrayList<>();
        try (RecordingServer server = RecordingServer.start(requests)) {
            ScenarioLauncherOptions options = ScenarioLauncherOptions.parse(new String[]{
                    "--base-url", server.baseUrl()
            });
            WorkerScenarioRegistrar registrar = new WorkerScenarioRegistrar(options,
                    new ScenarioClientFactory(server.baseUrl(), Duration.ofSeconds(5), Duration.ofSeconds(30), objectMapper));

            registrar.register(List.of(new WorkerScenarioSpec(
                    "api-worker-001",
                    "api-worker-key",
                    "sample-group",
                    "sample-node",
                    "polling",
                    "polling",
                    "api-online",
                    Map.of("region", "sg"),
                    List.of(WorkerEventBindingSpec.of("probe.phone.metadata", List.of("deviceProbe")))
            )), false);

            assertEquals(0, requests.stream()
                    .filter(request -> request.path().contains(":online")
                            || request.path().contains(":report-capability")
                            || request.path().contains(":report-state"))
                    .count());
        }
    }

    private static WorkerScenarioSpec worker(String workerId) {
        return new WorkerScenarioSpec(
                workerId,
                "worker-key",
                "sample-group",
                "sample-node",
                "polling",
                "polling",
                null,
                Map.of("region", "sg"),
                List.of(WorkerEventBindingSpec.of("probe.phone.metadata", List.of("deviceProbe")))
        );
    }

    private static long countPath(List<RecordedRequest> requests, String path) {
        return requests.stream().filter(request -> request.path().equals(path)).count();
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
            requests.add(new RecordedRequest(
                    exchange.getRequestURI().getPath(),
                    Map.of("X-mass-api-key", exchange.getRequestHeaders().getFirst("X-Mass-Api-Key")),
                    body
            ));
            byte[] response = "{\"code\":0,\"data\":{}}".getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, response.length);
            exchange.getResponseBody().write(response);
            exchange.close();
        }
    }
}
