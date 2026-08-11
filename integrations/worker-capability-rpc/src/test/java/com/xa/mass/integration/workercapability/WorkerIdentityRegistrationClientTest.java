package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerIdentityRegistrationClientTest {

    private static final String WORKER_ID =
            "server-issued-worker-id";

    @Test
    void repeatedRegistrationRecoversTheSamePlatformWorkerId()
            throws Exception {
        AtomicInteger requestCount = new AtomicInteger();
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (TestServer server = TestServer.start(
                200,
                Jsons.toJson(Map.of("workerId", WORKER_ID)),
                requestCount,
                requestBody
        )) {
            WorkerIdentityRegistrationClient client = client(server);

            assertEquals(
                    WORKER_ID,
                    client.registerOrRecoverWorkerId(
                            "phone-tools",
                            "installation-1"
                    )
            );
            assertEquals(
                    WORKER_ID,
                    client.registerOrRecoverWorkerId(
                            "phone-tools",
                            "installation-1"
                    )
            );
            assertEquals(2, requestCount.get());
            assertEquals(
                    Map.of(
                            "workerProperties",
                            Map.of(
                                    "clientWorkerKey",
                                    "installation-1"
                            )
                    ),
                    Jsons.parseObject(requestBody.get())
            );
        }
    }

    @Test
    void rejectsBlankWorkerId() throws Exception {
        try (TestServer server = TestServer.start(
                200,
                Jsons.toJson(Map.of("workerId", " ")),
                new AtomicInteger(),
                new AtomicReference<>()
        )) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> client(server).registerOrRecoverWorkerId(
                            "phone-tools",
                            "installation-1"
                    )
            );
            assertTrue(error.getMessage().contains("invalid workerId"));
        }
    }

    @Test
    void malformedResponsePreservesHttpStatus() throws Exception {
        try (TestServer server = TestServer.start(
                502,
                "not-json",
                new AtomicInteger(),
                new AtomicReference<>()
        )) {
            IllegalStateException error = assertThrows(
                    IllegalStateException.class,
                    () -> client(server).registerOrRecoverWorkerId(
                            "phone-tools",
                            "installation-1"
                    )
            );
            assertTrue(error.getMessage().contains("HTTP 502"));
        }
    }

    private static WorkerIdentityRegistrationClient client(
            TestServer server
    ) {
        return new WorkerIdentityRegistrationClient(
                new RuntimeApiHttpClient(
                        server.baseUri(),
                        Duration.ofSeconds(5)
                )
        );
    }

    private static final class TestServer implements AutoCloseable {
        private final HttpServer server;

        private TestServer(HttpServer server) {
            this.server = server;
        }

        static TestServer start(
                int status,
                String responseBody,
                AtomicInteger requestCount,
                AtomicReference<String> requestBody
        ) throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            server.createContext(
                    "/api/v1/worker-groups/phone-tools/workers:register",
                    exchange -> {
                        requestCount.incrementAndGet();
                        requestBody.set(new String(
                                exchange.getRequestBody().readAllBytes(),
                                StandardCharsets.UTF_8
                        ));
                        byte[] response = responseBody.getBytes(
                                StandardCharsets.UTF_8
                        );
                        exchange.sendResponseHeaders(status, response.length);
                        exchange.getResponseBody().write(response);
                        exchange.close();
                    }
            );
            server.start();
            return new TestServer(server);
        }

        URI baseUri() {
            return URI.create(
                    "http://127.0.0.1:" + server.getAddress().getPort()
            );
        }

        @Override
        public void close() {
            server.stop(0);
        }
    }
}
