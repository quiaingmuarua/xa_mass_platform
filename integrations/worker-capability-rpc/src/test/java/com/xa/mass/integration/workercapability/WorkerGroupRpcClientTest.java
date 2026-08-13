package com.xa.mass.integration.workercapability;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.sun.net.httpserver.HttpServer;
import com.xa.mass.integration.workercapability.runtimeapi.RuntimeApiHttpClient;
import com.xa.mass.integration.workercapability.runtimeapi.WorkerGroupRpcClient;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class WorkerGroupRpcClientTest {

    @Test
    void sendsAnOrdinaryUnrestrictedItemToTheGroupPath()
            throws Exception {
        AtomicReference<String> requestBody = new AtomicReference<>();
        try (TestServer server = TestServer.start(
                200,
                Map.of(
                        "status", "succeeded",
                        "messageId", "message-1",
                        "opaqueResultPayload", Jsons.toJson(
                                Map.of("valid", true, "value", "ok")
                        )
                ),
                requestBody
        )) {
            Map<String, Object> result = client(server).call(
                    "phone-tools",
                    "message-1",
                    "phone.lookup",
                    Map.of("number", "+14155552671"),
                    1_000
            );

            assertEquals("ok", result.get("value"));
            Map<String, Object> request = Jsons.parseObject(
                    requestBody.get()
            );
            @SuppressWarnings("unchecked")
            Map<String, Object> item =
                    (Map<String, Object>) request.get("item");
            assertEquals(Map.of(), item.get("allocationRule"));
            assertFalse(item.containsKey("workerGroupId"));
            assertFalse(item.containsKey("taskId"));
            assertFalse(item.containsKey("workerId"));
        }
    }

    @Test
    void rejectsAPendingCall() throws Exception {
        try (TestServer server = TestServer.start(
                202,
                Map.of("status", "pending", "messageId", "message-1"),
                new AtomicReference<>()
        )) {
            assertThrows(
                    IllegalStateException.class,
                    () -> client(server).call(
                            "phone-tools",
                            "message-1",
                            "phone.lookup",
                            Map.of(),
                            1_000
                    )
            );
        }
    }

    private static WorkerGroupRpcClient client(TestServer server) {
        return new WorkerGroupRpcClient(
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
                Map<String, Object> responseBody,
                AtomicReference<String> requestBody
        ) throws IOException {
            HttpServer server = HttpServer.create(
                    new InetSocketAddress("127.0.0.1", 0),
                    0
            );
            server.createContext(
                    "/api/v1/worker-groups/phone-tools/items:call",
                    exchange -> {
                        requestBody.set(new String(
                                exchange.getRequestBody().readAllBytes(),
                                StandardCharsets.UTF_8
                        ));
                        byte[] response = Jsons.toJson(responseBody).getBytes(
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
