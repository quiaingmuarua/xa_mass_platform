package com.xa.mass.transport.client.okhttp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class OkHttpWorkerControlClientTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    private MockWebServer server;
    private OkHttpWorkerControlClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        client = new OkHttpWorkerControlClient(
                URI.create(server.url("/").toString())
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        server.close();
    }

    @Test
    void registersClientKeyWithoutEmbeddingSecurityPolicy()
            throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"" + WORKER_ID + "\"}")
                .build());

        assertEquals(
                WORKER_ID,
                client.register(
                        "group a",
                        Map.of(
                                "clientWorkerKey",
                                "installation/1",
                                "runtime",
                                "java"
                        ),
                        Duration.ofSeconds(2)
                )
        );

        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(
                "/api/v1/worker-groups/group%20a/workers:register",
                request.getTarget()
        );
        assertNull(request.getHeaders().get("X-XA-Mass-Platform-Key"));
        assertEquals(
                Map.of(
                        "workerProperties",
                        Map.of(
                                "clientWorkerKey",
                                "installation/1",
                                "runtime",
                                "java"
                        )
                ),
                Jsons.parseObject(request.getBody().utf8())
        );
    }

    @Test
    void bindsWorkerAndReturnsOnlyTheEndpointUri() throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"ws://127.0.0.1:18083/connect\"}")
                .build());

        URI endpoint = client.bind(
                "group a",
                WORKER_ID,
                WorkerTransportType.WEBSOCKET,
                Map.of(
                        "clientWorkerKey",
                        "installation/1",
                        "region",
                        "local"
                ),
                Duration.ofSeconds(2)
        );

        assertEquals(
                URI.create("ws://127.0.0.1:18083/connect"),
                endpoint
        );
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(
                "/api/v1/worker-groups/group%20a/workers/"
                        + WORKER_ID + ":bind",
                request.getTarget()
        );
        assertEquals(
                Map.of(
                        "transportType",
                        "WEBSOCKET",
                        "workerProperties",
                        Map.of(
                                "clientWorkerKey",
                                "installation/1",
                                "region",
                                "local"
                        )
                ),
                Jsons.parseObject(request.getBody().utf8())
        );
    }

    @Test
    void rejectsInvalidRegistrationAndBindingResponses() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"worker-1\"}")
                .build());
        assertThrows(
                WorkerException.class,
                () -> client.register(
                        "group",
                        Map.of("clientWorkerKey", "installation"),
                        Duration.ofSeconds(2)
                )
        );

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"transportType\":\"SOCKET\","
                        + "\"endpointUri\":\"tcp://127.0.0.1:18084\"}")
                .build());
        assertThrows(
                WorkerException.class,
                () -> client.bind(
                        "group",
                        WORKER_ID,
                        WorkerTransportType.WEBSOCKET,
                        Map.of("clientWorkerKey", "installation"),
                        Duration.ofSeconds(2)
                )
        );
    }

    @Test
    void classifiesRetryableAndRejectedControlResponses() {
        server.enqueue(new MockResponse.Builder().code(503).build());
        WorkerException unavailable = assertThrows(
                WorkerException.class,
                () -> client.register(
                        "group",
                        Map.of("clientWorkerKey", "installation"),
                        Duration.ofSeconds(2)
                )
        );
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE,
                unavailable.errorCode()
        );

        server.enqueue(new MockResponse.Builder().code(409).build());
        WorkerException rejected = assertThrows(
                WorkerException.class,
                () -> client.register(
                        "group",
                        Map.of("clientWorkerKey", "installation"),
                        Duration.ofSeconds(2)
                )
        );
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_REJECTED,
                rejected.errorCode()
        );
    }
}
