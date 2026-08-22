package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.runtime.PreparedWorker;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class JavaOkHttpWorkerControlClientTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    private MockWebServer server;
    private OkHttpClient http;
    private JavaOkHttpWorkerControlClient client;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient();
        client = new JavaOkHttpWorkerControlClient(
                http,
                URI.create(server.url("/").toString())
        );
    }

    @AfterEach
    void tearDown() throws IOException {
        client.close();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        server.close();
    }

    @Test
    void preparesWorkerWithOneRequestAndReturnsIdentityAndEndpoint()
            throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"" + WORKER_ID + "\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":"
                        + "\"ws://127.0.0.1:18083/connect\"}")
                .build());

        PreparedWorker prepared = client.prepare(
                "group a",
                WorkerTransportType.WEBSOCKET,
                Map.of(
                        "clientWorkerKey", "installation/1",
                        "runtime", "java"
                ),
                Duration.ofSeconds(2)
        );

        assertEquals(WORKER_ID, prepared.workerId());
        assertEquals(
                URI.create("ws://127.0.0.1:18083/connect"),
                prepared.endpointUri()
        );
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(
                "/api/v1/worker-groups/group%20a/workers:prepare",
                request.getTarget()
        );
        assertNull(request.getHeaders().get("X-XA-Mass-Platform-Key"));
        assertEquals(
                Map.of(
                        "transportType", "WEBSOCKET",
                        "workerProperties", Map.of(
                                "clientWorkerKey", "installation/1",
                                "runtime", "java"
                        )
                ),
                Jsons.parseObject(request.getBody().utf8())
        );
    }

    @Test
    void rejectsIncompleteOrTransportMismatchedPreparationResponses() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"ws://127.0.0.1:18083\"}")
                .build());
        assertThrows(WorkerException.class, () -> prepare());

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"" + WORKER_ID + "\","
                        + "\"transportType\":\"SOCKET\","
                        + "\"endpointUri\":\"tcp://127.0.0.1:18084\"}")
                .build());
        assertThrows(WorkerException.class, () -> prepare());
    }

    @Test
    void classifiesFailuresAndCarriesSafeServerDiagnostics() {
        server.enqueue(new MockResponse.Builder()
                .code(503)
                .body("{\"code\":15007,\"message\":\"hidden\","
                        + "\"requestId\":\"request-1\"}")
                .build());
        WorkerException unavailable = assertThrows(
                WorkerException.class,
                this::prepare
        );
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE,
                unavailable.errorCode()
        );
        assertTrue(unavailable.getMessage().contains("code=15007"));
        assertTrue(unavailable.getMessage().contains("requestId=request-1"));
        assertTrue(!unavailable.getMessage().contains("hidden"));

        server.enqueue(new MockResponse.Builder().code(409).build());
        WorkerException rejected = assertThrows(
                WorkerException.class,
                this::prepare
        );
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_REJECTED,
                rejected.errorCode()
        );
    }

    private PreparedWorker prepare() throws IOException {
        return client.prepare(
                "group",
                WorkerTransportType.WEBSOCKET,
                Map.of("clientWorkerKey", "installation"),
                Duration.ofSeconds(2)
        );
    }
}
