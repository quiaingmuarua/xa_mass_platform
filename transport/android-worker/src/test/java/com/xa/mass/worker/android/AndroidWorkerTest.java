package com.xa.mass.worker.android;

import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WORKER_CONNECTION_IDENTIFY_EVENT_CODE;
import static com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint.ADAPTER;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertThrows;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;

import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventHandler;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import com.xa.mass.worker.execution.WorkerManagementEventDefinitions;
import com.xa.mass.worker.runtime.WorkerConnectionOptions;
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;

import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.Response;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerTest {

    private static final String WORKER_GROUP_ID = "android-demo-workers";
    private static final String WORKER_ID =
            "server-issued-worker-id";
    private static final String EVENT_CODE =
            "extension.worker.test.observe";

    private Application application;
    private MockWebServer server;
    private AndroidWorker worker;
    private AtomicReference<Map<String, Object>> properties;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidClientWorkerKeyStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();
        server = new MockWebServer();
        server.start();
        properties = new AtomicReference<>(Map.of(
                "runtime",
                "android",
                "region",
                "local"
        ));
    }

    @After
    public void tearDown() throws Exception {
        if (worker != null) {
            worker.close();
        }
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void firstStartPreparesAndExecutesThroughCoreTransport()
            throws Exception {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        CountDownLatch resultReceived = new CountDownLatch(1);
        AtomicReference<DeliveryReport> identity =
                new AtomicReference<>();
        AtomicReference<DeliveryReport> result = new AtomicReference<>();
        enqueuePrepare();
        server.enqueue(webSocketSession(new ClosingWebSocketListener() {
            @Override
            public void onMessage(WebSocket socket, String text) {
                if (identity.get() == null) {
                    identity.set(codec.decodeDeliveryReport(text));
                    socket.send(codec.encodeDeliveryCommand(
                            command("visible")
                    ));
                    return;
                }
                result.set(codec.decodeDeliveryReport(text));
                resultReceived.countDown();
            }
        }));
        AtomicReference<Context> providerContext = new AtomicReference<>();
        worker = worker(context -> {
            providerContext.set(context);
            return properties.get();
        });

        assertEquals(0, server.getRequestCount());

        worker.start();
        assertTrue(resultReceived.await(5, TimeUnit.SECONDS));

        RecordedRequest preparation = takeRequest();
        RecordedRequest socket = takeRequest();
        assertTrue(preparation.getTarget().endsWith("workers:prepare"));
        Map<String, Object> preparationBody = Jsons.parseObject(
                preparation.getBody().utf8()
        );
        assertEquals("CLIENT_KEY", preparationBody.get("workerKind"));
        @SuppressWarnings("unchecked")
        Map<String, Object> registeredProperties =
                (Map<String, Object>) preparationBody.get("workerProperties");
        String clientWorkerKey = (String) registeredProperties.get(
                "clientWorkerKey"
        );
        assertEquals(
                UUID.fromString(clientWorkerKey).toString(),
                clientWorkerKey
        );
        assertEquals("android", registeredProperties.get("runtime"));
        assertFalse(preparationBody
                .containsKey("clientWorkerKey"));
        assertEquals(
                "/api/v1/worker-delivery/websocket",
                socket.getTarget()
        );
        assertEquals(application, providerContext.get());
        assertNotNull(identity.get());
        assertEquals(ADAPTER, identity.get().dst());
        assertEquals(
                WORKER_CONNECTION_IDENTIFY_EVENT_CODE,
                identity.get().messageType()
        );
        assertEquals("200", identity.get().outcomeCode());
        assertEquals(DeliveryEndpoint.WORKER, identity.get().src());
        assertEquals(WORKER_ID, identity.get().sourceId());
        assertEquals("null", identity.get().payload());
        assertEquals("", identity.get().forward());
        assertNotNull(result.get());
        assertEquals("200", result.get().outcomeCode());
        assertEquals(
                "visible",
                Jsons.parseObject(result.get().payload()).get("observed")
        );
    }

    @Test
    public void stopThenStartKeepsClientKeyAndPreparesAgain()
            throws Exception {
        enqueuePrepare();
        server.enqueue(webSocketSession(new ClosingWebSocketListener() {
        }));
        worker = worker(context -> properties.get());
        worker.start();
        RecordedRequest firstPrepare = takeRequest();
        takeRequest();
        String clientWorkerKey = (String) bindingProperties(firstPrepare).get(
                "clientWorkerKey"
        );
        assertTrue(firstPrepare.getTarget().endsWith("workers:prepare"));

        worker.stop();
        await(() -> worker.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        enqueuePrepare();
        server.enqueue(webSocketSession(new ClosingWebSocketListener() {
        }));
        worker.start();

        RecordedRequest secondPrepare = takeRequest();
        RecordedRequest secondSocket = takeRequest();
        assertTrue(secondPrepare.getTarget().endsWith(
                "/workers:prepare"
        ));
        assertEquals(
                clientWorkerKey,
                bindingProperties(secondPrepare).get("clientWorkerKey")
        );
        assertEquals(
                "/api/v1/worker-delivery/websocket",
                secondSocket.getTarget()
        );
        assertEquals(WORKER_ID, worker.snapshot().workerId());
    }

    @Test
    public void changedPropertiesAreLoadedByTheNextExplicitStart()
            throws Exception {
        enqueuePrepare();
        server.enqueue(webSocketSession(new ClosingWebSocketListener() {
        }));
        worker = worker(context -> properties.get());
        worker.start();
        takeRequest();
        takeRequest();

        properties.set(Map.of(
                "runtime",
                "android",
                "region",
                "updated"
        ));
        worker.stop();
        await(() -> worker.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        enqueuePrepare();
        server.enqueue(webSocketSession(new ClosingWebSocketListener() {
        }));
        worker.start();
        RecordedRequest refreshed = takeRequest();
        takeRequest();

        assertTrue(refreshed.getTarget().endsWith(
                "/workers:prepare"
        ));
        assertEquals(
                "updated",
                bindingProperties(refreshed).get("region")
        );
        assertEquals(WorkerLifecycle.State.RUNNING,
                worker.snapshot().state());
    }

    @Test
    public void reservedClientWorkerKeyFromProviderFailsBeforeNetwork()
            throws Exception {
        worker = worker(context -> Map.of(
                "clientWorkerKey",
                "caller-owned"
        ));

        worker.start();
        await(() -> worker.snapshot().state()
                == WorkerLifecycle.State.STOPPED
                && worker.snapshot().diagnosticMessage() != null);

        assertEquals(0, server.getRequestCount());
        assertTrue(worker.snapshot().diagnosticMessage().contains(
                "IllegalArgumentException"
        ));
    }

    @Test
    public void createOwnsItsPlatformResourcesAndAllowsNoExtensions() {
        AndroidWorker built = AndroidWorker.create(
                application,
                URI.create(server.url("/").toString()),
                WORKER_GROUP_ID,
                context -> properties.get()
        );

        built.close();
    }

    @Test
    public void hostCannotOverrideDefaultManagementEvents() {
        assertThrows(
                IllegalArgumentException.class,
                () -> AndroidWorker.create(
                        application,
                        URI.create(server.url("/").toString()),
                        WORKER_GROUP_ID,
                        context -> properties.get(),
                        List.of(WorkerEventDefinition.extension(
                                WorkerManagementEventDefinitions.PROBE_EVENT,
                                payload -> null,
                                ignored -> "null"
                        ))
                )
        );
    }

    @Test
    public void onlyOneActiveWorkerPerApplicationAndGroup() throws Exception {
        enqueuePrepare();
        server.enqueue(webSocketSession(new ClosingWebSocketListener() {
        }));
        worker = worker(context -> properties.get());
        AndroidWorker duplicate = worker(context -> properties.get());
        try {
            worker.start();
            assertThrows(IllegalStateException.class, duplicate::start);
        } finally {
            duplicate.close();
        }
    }

    @Test
    public void processLeaseIsReleasedWhenTheWorkerRunStops()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        CountDownLatch duplicateOpen = new CountDownLatch(1);
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        enqueuePrepare();
        server.enqueue(webSocketSession(new ClosingWebSocketListener() {
            @Override
            public void onMessage(WebSocket socket, String text) {
                DeliveryReport identity = codec.decodeDeliveryReport(text);
                if (identity != null
                        && WORKER_CONNECTION_IDENTIFY_EVENT_CODE.equals(
                        identity.messageType()
                )) {
                    socket.send(codec.encodeDeliveryCommand(
                            command("blocked")
                    ));
                }
            }

        }));
        worker = worker(
                context -> properties.get(),
                parameters -> {
                    entered.countDown();
                    assertTrue(release.await(5, TimeUnit.SECONDS));
                    return Jsons.toJson(Map.of(
                            "observed",
                            parameters.get("value")
                    ));
                }
        );
        AndroidWorker duplicate = worker(context -> properties.get());
        try {
            worker.start();
            assertTrue(entered.await(5, TimeUnit.SECONDS));

            worker.stop();
            assertEquals(
                    WorkerLifecycle.State.STOPPED,
                    worker.snapshot().state()
            );

            enqueuePrepare();
            server.enqueue(webSocketSession(new ClosingWebSocketListener() {
                @Override
                public void onOpen(
                        WebSocket socket,
                        Response response
                ) {
                    duplicateOpen.countDown();
                }

            }));
            duplicate.start();
            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    duplicate.snapshot().state()
            );
            assertTrue(duplicateOpen.await(5, TimeUnit.SECONDS));
        } finally {
            release.countDown();
            duplicate.close();
        }
    }

    private AndroidWorker worker(AndroidWorkerProperties provider) {
        return worker(
                provider,
                parameters -> Jsons.toJson(Map.of(
                        "observed",
                        parameters.get("value")
                ))
        );
    }

    private AndroidWorker worker(
            AndroidWorkerProperties provider,
            WorkerEventHandler<Map<String, Object>> handler
    ) {
        return AndroidWorker.create(
                application,
                URI.create(server.url("/").toString()),
                WORKER_GROUP_ID,
                provider,
                List.of(WorkerEventDefinition.extension(
                        "test.observe",
                        WorkerEventParameterResolvers.jsonMap(),
                        handler
                )),
                WorkerConnectionOptions.of(
                        Duration.ofSeconds(2),
                        TextMessageReconnectPolicy.of(
                                20,
                                Duration.ofMillis(20),
                                Duration.ofMillis(100)
                        )
                )
        );
    }

    private void enqueuePrepare() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"" + WORKER_ID + "\","
                        + "\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"" + endpointUri() + "\"}")
                .build());
    }

    private MockResponse webSocketSession(WebSocketListener listener) {
        return new MockResponse.Builder()
                .webSocketUpgrade(listener)
                .build();
    }

    private URI endpointUri() {
        return URI.create(server.url(
                "/api/v1/worker-delivery/websocket"
        ).toString().replaceFirst("^http", "ws"));
    }

    private static DeliveryCommand command(String value) {
        return DeliveryCommand.create(
                DeliveryEndpoint.TASK,
                DeliveryEndpoint.WORKER,
                EVENT_CODE,
                System.currentTimeMillis() + 30_000,
                Jsons.toJson(Map.of("value", value)),
                "android-forward"
        );
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> bindingProperties(
            RecordedRequest request
    ) {
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
        assertTrue("condition was not satisfied", check.satisfied());
    }

    @FunctionalInterface
    private interface Check {
        boolean satisfied();
    }

    private static class ClosingWebSocketListener extends WebSocketListener {

        @Override
        public void onClosing(
                WebSocket socket,
                int code,
                String reason
        ) {
            socket.close(code, reason);
        }
    }
}
