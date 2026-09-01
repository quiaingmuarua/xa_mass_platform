package com.xa.mass.worker.android;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

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
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.OkHttpClient;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public final class AndroidOkHttpWorkerControlClientTest {

    private MockWebServer server;
    private OkHttpClient http;
    private AndroidOkHttpWorkerControlClient client;

    @Before
    public void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
        http = new OkHttpClient();
        client = new AndroidOkHttpWorkerControlClient(
                http,
                URI.create(server.url("/").toString())
        );
    }

    @After
    public void tearDown() throws IOException {
        client.close();
        http.dispatcher().cancelAll();
        http.connectionPool().evictAll();
        http.dispatcher().executorService().shutdownNow();
        server.close();
    }

    @Test
    public void sendsClientKeyPreparationAndReturnsOpaqueIdentity()
            throws Exception {
        server.enqueue(success(
                "server-issued-worker-id",
                "ws://127.0.0.1:18083/connect"
        ));

        PreparedWorker prepared = client.prepare(
                "group a",
                WorkerTransportType.WEBSOCKET,
                Map.of(
                        "clientWorkerKey",
                        "installation-1",
                        "runtime",
                        "android"
                ),
                Duration.ofSeconds(2L)
        );

        assertEquals("server-issued-worker-id", prepared.workerId());
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
        assertEquals(
                Map.of(
                        "workerKind", "CLIENT_KEY",
                        "transportType", "WEBSOCKET",
                        "workerProperties", Map.of(
                                "clientWorkerKey", "installation-1",
                                "runtime", "android"
                        )
                ),
                Jsons.parseObject(request.getBody().utf8())
        );
    }

    @Test
    public void classifiesRejectedUnavailableAndInvalidResponses() {
        server.enqueue(new MockResponse.Builder()
                .code(503)
                .body("{\"code\":15007,\"requestId\":\"request-1\"}")
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

        server.enqueue(new MockResponse.Builder().code(409).build());
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_REJECTED,
                assertThrows(WorkerException.class, this::prepare).errorCode()
        );

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("not-json")
                .build());
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_RESPONSE_INVALID,
                assertThrows(WorkerException.class, this::prepare).errorCode()
        );

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"worker\","
                        + "\"transportType\":\"SOCKET\","
                        + "\"endpointUri\":\"tcp://127.0.0.1:18084\"}")
                .build());
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_RESPONSE_INVALID,
                assertThrows(WorkerException.class, this::prepare).errorCode()
        );

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"worker\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"ws://127.0.0.1:18083\","
                        + "\"extra\":true}")
                .build());
        assertEquals(
                WorkerErrorCode.WORKER_CONTROL_RESPONSE_INVALID,
                assertThrows(WorkerException.class, this::prepare).errorCode()
        );
    }

    @Test
    public void closeCancelsAnActivePreparationAndIsTerminal()
            throws Exception {
        server.enqueue(success("worker", "ws://127.0.0.1:18083/connect")
                .newBuilder()
                .bodyDelay(30L, TimeUnit.SECONDS)
                .build());
        AtomicReference<Throwable> failure = new AtomicReference<>();
        Thread request = new Thread(() -> {
            try {
                prepare();
            } catch (Throwable error) {
                failure.set(error);
            }
        }, "android-worker-control-cancel-test");

        request.start();
        assertNotNull(server.takeRequest(2, TimeUnit.SECONDS));
        client.close();
        request.join(2_000L);

        assertFalse(request.isAlive());
        assertNotNull(failure.get());
        assertThrows(IllegalStateException.class, this::prepare);
    }

    private PreparedWorker prepare() throws IOException {
        return client.prepare(
                "group",
                WorkerTransportType.WEBSOCKET,
                Map.of("clientWorkerKey", "installation"),
                Duration.ofSeconds(2L)
        );
    }

    private static MockResponse success(String workerId, String endpointUri) {
        return new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"" + workerId + "\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"" + endpointUri + "\"}")
                .build();
    }
}
