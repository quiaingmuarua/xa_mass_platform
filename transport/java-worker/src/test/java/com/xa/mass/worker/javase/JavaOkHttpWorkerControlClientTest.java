package com.xa.mass.worker.javase;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.runtime.WorkerPreparationResult;
import com.xa.mass.workerdelivery.json.Jsons;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.List;
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
    void acceptsMixedBatchWithoutInventingEndpointForCooldown() throws Exception {
        var rows = new java.util.ArrayList<Map<String, Object>>();
        var properties = new java.util.ArrayList<Map<String, String>>();
        for (int index = 0; index < 100; index++) {
            rows.add(index % 2 == 0
                    ? Map.of("status", "cooldown", "workerId", "worker-" + index, "cooldownUntilMillis", 123456L)
                    : Map.of("status", "prepared", "workerId", "worker-" + index,
                            "transportType", "WEBSOCKET", "endpointUri", "ws://127.0.0.1:18083/ws"));
            properties.add(Map.of("labSlot", Integer.toString(index)));
        }
        server.enqueue(new MockResponse.Builder().code(200).body(Jsons.toJson(rows)).build());
        var outcomes = client.prepareBatch("SCENARIO_LAB", "group", WorkerTransportType.WEBSOCKET,
                properties, Duration.ofSeconds(2));
        assertEquals(100, outcomes.size());
        assertNull(outcomes.get(0).preparedWorker());
        assertEquals(123456L, outcomes.get(0).cooldownUntilMillis());
        assertEquals("worker-99", outcomes.get(99).workerId());
        assertNotNull(outcomes.get(99).preparedWorker());
        rows.set(99, Map.of("status", "cooldown", "workerId", "worker-99", "cooldownUntilMillis", "bad"));
        server.enqueue(new MockResponse.Builder().code(200).body(Jsons.toJson(rows)).build());
        assertThrows(WorkerException.class, () -> client.prepareBatch("SCENARIO_LAB", "group",
                WorkerTransportType.WEBSOCKET, properties, Duration.ofSeconds(2)));
        assertEquals(2, server.getRequestCount());
    }

    @Test
    void preparesWorkerWithOneRequestAndReturnsIdentityAndEndpoint()
            throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"status\":\"prepared\",\"workerId\":\"" + WORKER_ID + "\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":"
                        + "\"ws://127.0.0.1:18083/connect\"}")
                .build());

        WorkerPreparationResult prepared = client.prepare(
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
                prepared.preparedWorker().endpointUri()
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
                .body("{\"status\":\"prepared\",\"workerId\":\"\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"ws://127.0.0.1:18083\"}")
                .build());
        assertThrows(WorkerException.class, () -> prepare());

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"status\":\"prepared\",\"workerId\":\"" + WORKER_ID + "\","
                        + "\"transportType\":\"SOCKET\","
                        + "\"endpointUri\":\"tcp://127.0.0.1:18084\"}")
                .build());
        assertThrows(WorkerException.class, () -> prepare());
    }

    @Test
    void preparesBatchAndCorrelatesStrictResultsByRequestOrder()
            throws Exception {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("["
                        + "{\"status\":\"prepared\",\"workerId\":\"worker-a\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"ws://127.0.0.1:18083/a\"},"
                        + "{\"status\":\"prepared\",\"workerId\":\"worker-b\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"ws://127.0.0.1:18083/b\"}"
                        + "]")
                .build());

        List<WorkerPreparationResult> prepared = client.prepareBatch(
                "SCENARIO_LAB",
                "group a",
                WorkerTransportType.WEBSOCKET,
                List.of(
                        Map.of(
                                "labInventoryKey", "worker-a.jsonl",
                                "labInventoryLine", "1",
                                "labSlot", "1"
                        ),
                        Map.of(
                                "labInventoryKey", "worker-a.jsonl",
                                "labInventoryLine", "2",
                                "labSlot", "2"
                        )
                ),
                Duration.ofSeconds(2)
        );

        assertEquals("worker-a", prepared.get(0).workerId());
        assertEquals("worker-b", prepared.get(1).workerId());
        RecordedRequest request = server.takeRequest(1, TimeUnit.SECONDS);
        assertNotNull(request);
        assertEquals(
                "/api/v1/worker-groups/group%20a/workers:prepare-batch",
                request.getTarget()
        );
        assertEquals(
                List.of(
                        Map.of(
                                "workerKind", "SCENARIO_LAB",
                                "transportType", "WEBSOCKET",
                                "workerProperties", Map.of(
                                        "labInventoryKey", "worker-a.jsonl",
                                        "labInventoryLine", "1",
                                        "labSlot", "1"
                                )
                        ),
                        Map.of(
                                "workerKind", "SCENARIO_LAB",
                                "transportType", "WEBSOCKET",
                                "workerProperties", Map.of(
                                        "labInventoryKey", "worker-a.jsonl",
                                        "labInventoryLine", "2",
                                        "labSlot", "2"
                                )
                        )
                ),
                Jsons.parseArray(request.getBody().utf8())
        );
    }

    @Test
    void rejectsIncompleteDuplicateOrExtraBatchResults() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("[]")
                .build());
        assertThrows(WorkerException.class, () -> client.prepareBatch(
                "SCENARIO_LAB",
                "group",
                WorkerTransportType.WEBSOCKET,
                List.of(Map.of("labSlot", "1")),
                Duration.ofSeconds(2)
        ));

        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("[{\"clientWorkerKey\":\"legacy\","
                        + "\"workerId\":\"worker-a\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"ws://127.0.0.1:18083\"}]")
                .build());
        assertThrows(WorkerException.class, () -> client.prepareBatch(
                "SCENARIO_LAB",
                "group",
                WorkerTransportType.WEBSOCKET,
                List.of(Map.of("labSlot", "1")),
                Duration.ofSeconds(2)
        ));
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

        server.enqueue(new MockResponse.Builder().code(429).build());
        assertEquals(WorkerErrorCode.WORKER_CONTROL_UNAVAILABLE,
                assertThrows(WorkerException.class, this::prepare).errorCode());

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

    private WorkerPreparationResult prepare() throws IOException {
        return client.prepare(
                "group",
                WorkerTransportType.WEBSOCKET,
                Map.of("clientWorkerKey", "installation"),
                Duration.ofSeconds(2)
        );
    }
}
