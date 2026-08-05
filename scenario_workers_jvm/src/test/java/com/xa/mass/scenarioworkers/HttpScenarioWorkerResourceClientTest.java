package com.xa.mass.scenarioworkers;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class HttpScenarioWorkerResourceClientTest {

    @Test
    void usesPublicResourcePathsAndParsesPerFieldIndexResults()
            throws Exception {
        List<CapturedRequest> requests = new ArrayList<>();
        AtomicInteger sequence = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            requests.add(capture(exchange));
            String response = switch (sequence.getAndIncrement()) {
                case 0 -> "{\"status\":\"ok\",\"reason\":null}";
                case 1 -> "{\"status\":\"noop\",\"reason\":null}";
                default -> """
                        {"results":{"index.worker.region":{
                          "status":"rejected","reason":"not configured"
                        }}}
                        """;
            };
            respond(exchange, 200, response);
        });
        try {
            HttpScenarioWorkerResourceClient client = client(server);
            client.registerWorker(
                    "group a",
                    "worker/1",
                    "adapter-1",
                    Map.of("region", "local"),
                    Duration.ofSeconds(2)
            );
            client.updateWorkerProperties(
                    "group a",
                    "worker/1",
                    Map.of("region", "local"),
                    Duration.ofSeconds(2)
            );
            Map<String, ScenarioWorkerResourceResult> index =
                    client.updateIndexedProperties(
                            "group a",
                            "worker/1",
                            Map.of("index.worker.region", "local"),
                            Duration.ofSeconds(2)
                    );

            assertThat(index.get("index.worker.region").status())
                    .isEqualTo("rejected");
            assertThat(requests).extracting(CapturedRequest::method)
                    .containsExactly("PUT", "PUT", "PATCH");
            assertThat(requests).extracting(CapturedRequest::rawPath)
                    .containsExactly(
                            "/api/v1/worker-groups/group%20a/workers/worker%2F1",
                            "/api/v1/worker-groups/group%20a/workers/worker%2F1/worker-properties",
                            "/api/v1/worker-groups/group%20a/workers/worker%2F1/indexed-properties"
                    );
            assertThat(requests.get(0).body())
                    .isEqualTo("""
                            {"endpointManagerId":"adapter-1","workerProperties":{"region":"local"}}""");
            assertThat(requests.get(1).body())
                    .isEqualTo("{\"properties\":{\"region\":\"local\"}}");
            assertThat(requests.get(2).body())
                    .isEqualTo("{\"updates\":{\"index.worker.region\":\"local\"}}");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsHttpFailureAndMalformedResponses() throws Exception {
        AtomicInteger sequence = new AtomicInteger();
        HttpServer server = startServer(exchange -> {
            if (sequence.getAndIncrement() == 0) {
                respond(exchange, 503, "{}");
            } else {
                respond(exchange, 200, "{broken");
            }
        });
        try {
            HttpScenarioWorkerResourceClient client = client(server);

            assertThatThrownBy(() -> client.registerWorker(
                    "group",
                    "worker",
                    "adapter",
                    Map.of(),
                    Duration.ofSeconds(2)
            )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                    .hasMessageContaining("HTTP 503");
            assertThatThrownBy(() -> client.updateWorkerProperties(
                    "group",
                    "worker",
                    Map.of(),
                    Duration.ofSeconds(2)
            )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                    .hasMessageContaining("invalid JSON");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void requestTimeoutIsReportedAsResourceFailure() throws Exception {
        HttpServer server = startServer(exchange -> {
            try {
                Thread.sleep(250L);
            } catch (InterruptedException error) {
                Thread.currentThread().interrupt();
            }
            respond(exchange, 200, "{\"status\":\"ok\",\"reason\":null}");
        });
        try {
            HttpScenarioWorkerResourceClient client = client(server);

            assertThatThrownBy(() -> client.registerWorker(
                    "group",
                    "worker",
                    "adapter",
                    Map.of(),
                    Duration.ofMillis(20)
            )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                    .hasMessageContaining("request failed");
        } finally {
            server.stop(0);
        }
    }

    private static HttpScenarioWorkerResourceClient client(
            HttpServer server
    ) {
        return new HttpScenarioWorkerResourceClient(URI.create(
                "http://127.0.0.1:" + server.getAddress().getPort()
        ));
    }

    private static HttpServer startServer(Handler handler)
            throws IOException {
        HttpServer server = HttpServer.create(
                new InetSocketAddress("127.0.0.1", 0),
                0
        );
        server.createContext("/", exchange -> handler.handle(exchange));
        server.start();
        return server;
    }

    private static CapturedRequest capture(HttpExchange exchange)
            throws IOException {
        return new CapturedRequest(
                exchange.getRequestMethod(),
                exchange.getRequestURI().getRawPath(),
                new String(
                        exchange.getRequestBody().readAllBytes(),
                        StandardCharsets.UTF_8
                )
        );
    }

    private static void respond(
            HttpExchange exchange,
            int status,
            String body
    ) throws IOException {
        byte[] encoded = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add(
                "Content-Type",
                "application/json"
        );
        exchange.sendResponseHeaders(status, encoded.length);
        exchange.getResponseBody().write(encoded);
        exchange.close();
    }

    @FunctionalInterface
    private interface Handler {
        void handle(HttpExchange exchange) throws IOException;
    }

    private record CapturedRequest(
            String method,
            String rawPath,
            String body
    ) {
    }
}
