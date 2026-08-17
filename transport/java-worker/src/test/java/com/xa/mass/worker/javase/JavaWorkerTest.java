package com.xa.mass.worker.javase;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.WORKER;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerManagementEventDefinitions;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerIdentityStore;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.TimeUnit;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.WebSocketListener;

class JavaWorkerTest {

    private static final String WORKER_ID =
            "server-issued-worker-id";

    private MockWebServer server;
    private JavaWorker worker;

    @BeforeEach
    void setUp() throws IOException {
        server = new MockWebServer();
        server.start();
    }

    @AfterEach
    void tearDown() throws IOException {
        if (worker != null) {
            worker.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    void injectsFixedClientKeyAndDelegatesEachStartToSharedRuntime()
            throws Exception {
        MemoryIdentityStore identity = new MemoryIdentityStore();
        enqueueSession(true);
        worker = worker(identity, Map.of("runtime", "java"));

        assertEquals(0, server.getRequestCount());

        worker.start();
        RecordedRequest register = takeRequest();
        RecordedRequest firstBind = takeRequest();
        takeRequest();

        assertEquals(WORKER_ID, identity.workerId);
        assertEquals(
                "fixed-installation",
                properties(register).get("clientWorkerKey")
        );
        assertEquals("java", properties(register).get("runtime"));
        assertEquals(
                "fixed-installation",
                properties(firstBind).get("clientWorkerKey")
        );

        worker.stop();
        await(() -> worker.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        enqueueSession(false);
        worker.start();
        RecordedRequest secondBind = takeRequest();
        takeRequest();
        assertTrue(secondBind.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        assertEquals(WORKER_ID, worker.snapshot().workerId());
    }

    @Test
    void createRequiresExplicitIdentityStore() {
        assertThrows(
                NullPointerException.class,
                () -> JavaWorker.create(
                        URI.create(server.url("/").toString()),
                        "group-1",
                        "fixed-installation",
                        null,
                        WorkerTransportType.WEBSOCKET,
                        Map::of
                )
        );
    }

    @Test
    void createOwnsItsPlatformResourcesAndAllowsNoExtensions() {
        JavaWorker built = JavaWorker.create(
                URI.create(server.url("/").toString()),
                "group-1",
                "fixed-installation",
                WorkerIdentityStore.noCache(),
                WorkerTransportType.WEBSOCKET,
                Map::of
        );

        built.close();
    }

    @Test
    void hostCannotOverrideDefaultManagementEvents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorker.create(
                        URI.create(server.url("/").toString()),
                        "group-1",
                        "fixed-installation",
                        WorkerIdentityStore.noCache(),
                        WorkerTransportType.WEBSOCKET,
                        Map::of,
                        List.of(WorkerEventDefinition.extension(
                                WorkerManagementEventDefinitions.PROBE_EVENT,
                                payload -> null,
                                ignored -> "null"
                        ))
                )
        );
    }

    @Test
    void callerCannotOverrideReservedClientWorkerKey() throws Exception {
        worker = worker(
                WorkerIdentityStore.noCache(),
                Map.of("clientWorkerKey", "caller-owned")
        );

        worker.start();
        await(() -> worker.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        assertEquals(0, server.getRequestCount());
        assertTrue(worker.snapshot().diagnosticMessage().contains(
                "IllegalArgumentException"
        ));
    }

    private JavaWorker worker(
            WorkerIdentityStore identity,
            Map<String, Object> properties
    ) {
        return JavaWorker.create(
                URI.create(server.url("/").toString()),
                "group-1",
                "fixed-installation",
                identity,
                WorkerTransportType.WEBSOCKET,
                () -> properties,
                definitions(),
                WorkerConnectionOptions.of(
                        Duration.ofSeconds(2),
                        reconnectPolicy()
                )
        );
    }

    @Test
    void pollingIsRejectedByTheTextMessageAssembly() {
        assertThrows(
                IllegalArgumentException.class,
                () -> JavaWorker.create(
                        URI.create(server.url("/").toString()),
                        "group-1",
                        "fixed-installation",
                        WorkerIdentityStore.noCache(),
                        WorkerTransportType.POLLING,
                        Map::of
                )
        );
    }

    @Test
    void socketTypeSelectsTheLineClient() throws Exception {
        MemoryIdentityStore identity = new MemoryIdentityStore();
        identity.workerId = WORKER_ID;
        try (ServerSocket lineServer = new ServerSocket(0)) {
            URI endpoint = URI.create(
                    "tcp://127.0.0.1:" + lineServer.getLocalPort()
            );
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("{\"transportType\":\"SOCKET\","
                            + "\"endpointUri\":\"" + endpoint + "\"}")
                    .build());
            worker = JavaWorker.create(
                    URI.create(server.url("/").toString()),
                    "group-1",
                    "fixed-installation",
                    identity,
                    WorkerTransportType.SOCKET,
                    () -> Map.of("runtime", "java"),
                    definitions(),
                    WorkerConnectionOptions.of(
                            Duration.ofSeconds(2),
                            reconnectPolicy()
                    )
            );

            worker.start();
            RecordedRequest bind = takeRequest();
            assertEquals(
                    "SOCKET",
                    Jsons.parseObject(bind.getBody().utf8())
                            .get("transportType")
            );
            lineServer.setSoTimeout(5_000);
            try (java.net.Socket socket = lineServer.accept();
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(
                                 socket.getInputStream(),
                                 StandardCharsets.UTF_8
                         )
                 )) {
                DeliveryReport identityFrame = new WorkerDeliveryCodec()
                        .decodeDeliveryReport(
                                reader.readLine()
                        );
                assertNotNull(identityFrame);
                assertEquals(WORKER, identityFrame.src());
                assertEquals(WORKER_ID, identityFrame.sourceId());
                assertEquals(ADAPTER, identityFrame.dst());
                assertEquals(
                        WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                        identityFrame.messageType()
                );
                assertEquals("200", identityFrame.outcomeCode());
                assertEquals("null", identityFrame.payload());
                assertEquals("", identityFrame.forward());
            }
        }
    }

    private static List<WorkerEventDefinition<?>> definitions() {
        return List.of(WorkerEventDefinition.extension(
                "test.observe",
                WorkerEventParameterResolvers.jsonMap(),
                parameters -> "null"
        ));
    }

    private static TextMessageReconnectPolicy reconnectPolicy() {
        return TextMessageReconnectPolicy.of(
                20,
                Duration.ofMillis(20),
                Duration.ofMillis(100)
        );
    }

    private void enqueueSession(boolean registration) {
        if (registration) {
            server.enqueue(new MockResponse.Builder()
                    .code(200)
                    .body("{\"workerId\":\"" + WORKER_ID + "\"}")
                    .build());
        }
        URI endpoint = URI.create(server.url(
                "/api/v1/worker-delivery/websocket"
        ).toString().replaceFirst("^http", "ws"));
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"" + endpoint + "\"}")
                .build());
        server.enqueue(new MockResponse.Builder()
                .webSocketUpgrade(new WebSocketListener() {
                })
                .build());
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> properties(RecordedRequest request) {
        return (Map<String, Object>) Jsons.parseObject(
                request.getBody().utf8()
        ).get("workerProperties");
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (System.nanoTime() < deadline) {
            if (check.satisfied()) {
                return;
            }
            Thread.sleep(10);
        }
        assertTrue(check.satisfied(), "condition was not satisfied");
    }

    @FunctionalInterface
    private interface Check {
        boolean satisfied() throws Exception;
    }

    private static final class MemoryIdentityStore
            implements WorkerIdentityStore {

        private String workerId;

        @Override
        public Optional<String> loadWorkerId() {
            return Optional.ofNullable(workerId);
        }

        @Override
        public void saveWorkerId(String value) {
            workerId = value;
        }
    }
}
