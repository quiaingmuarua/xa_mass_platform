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
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class HttpScenarioWorkerIndexClientTest {

    @Test
    void usesOnlyIndexedPropertiesPathAndParsesFieldResults()
            throws Exception {
        AtomicReference<CapturedRequest> captured = new AtomicReference<>();
        HttpServer server = startServer(exchange -> {
            captured.set(capture(exchange));
            respond(exchange, 200, """
                    {"results":{"index.worker.region":{
                      "status":"not_found","reason":"hello pending"
                    }}}
                    """);
        });
        try {
            HttpScenarioWorkerIndexClient client = client(server);
            Map<String, ScenarioWorkerIndexResult> results =
                    client.updateIndexedProperties(
                            "group a",
                            "worker/1",
                            Map.of("index.worker.region", "local"),
                            Duration.ofSeconds(2)
                    );

            assertThat(results.get("index.worker.region").notFound())
                    .isTrue();
            assertThat(captured.get().method()).isEqualTo("PATCH");
            assertThat(captured.get().rawPath()).isEqualTo(
                    "/api/v1/worker-groups/group%20a/workers/"
                            + "worker%2F1/indexed-properties"
            );
            assertThat(captured.get().body()).isEqualTo(
                    "{\"updates\":{\"index.worker.region\":\"local\"}}"
            );
        } finally {
            server.stop(0);
        }
    }

    @Test
    void rejectsHttpAndProtocolFailures() throws Exception {
        HttpServer server = startServer(exchange ->
                respond(exchange, 503, "{}")
        );
        try {
            HttpScenarioWorkerIndexClient client = client(server);
            assertThatThrownBy(() -> client.updateIndexedProperties(
                    "group",
                    "worker",
                    Map.of("index.worker.region", "local"),
                    Duration.ofSeconds(2)
            )).isInstanceOf(ScenarioWorkerAssemblyException.class)
                    .hasMessageContaining("HTTP 503");
        } finally {
            server.stop(0);
        }
    }

    private static HttpScenarioWorkerIndexClient client(HttpServer server) {
        return new HttpScenarioWorkerIndexClient(URI.create(
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
