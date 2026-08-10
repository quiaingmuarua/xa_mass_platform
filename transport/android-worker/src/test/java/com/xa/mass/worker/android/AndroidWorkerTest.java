package com.xa.mass.worker.android;

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
import com.xa.mass.worker.runtime.WorkerLifecycle;
import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;

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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWorkerTest {

    private static final String WORKER_GROUP_ID = "android-demo-workers";
    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final String EVENT_CODE = "test.observe";

    private Application application;
    private MockWebServer server;
    private AndroidWorker worker;
    private AtomicReference<Map<String, Object>> properties;
    private ExecutorService handlerExecutor;

    @Before
    public void setUp() throws Exception {
        handlerExecutor = Executors.newSingleThreadExecutor();
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
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
        handlerExecutor.shutdownNow();
        if (server != null) {
            server.close();
        }
    }

    @Test
    public void firstStartRegistersBindsAndExecutesThroughCoreTransport()
            throws Exception {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        CountDownLatch resultReceived = new CountDownLatch(1);
        AtomicReference<WorkerConnectionBind> connectionBind =
                new AtomicReference<>();
        AtomicReference<WorkerResult> result = new AtomicReference<>();
        enqueueRegister();
        enqueueBind();
        server.enqueue(webSocketSession(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket socket, String text) {
                if (connectionBind.get() == null) {
                    connectionBind.set(
                            codec.decodeWorkerConnectionBind(text)
                    );
                    socket.send(codec.encodeWorkerCommand(
                            command("visible")
                    ));
                    return;
                }
                result.set(codec.decodeWorkerResult(text));
                resultReceived.countDown();
            }
        }));
        AtomicReference<Context> providerContext = new AtomicReference<>();
        worker = worker(context -> {
            providerContext.set(context);
            return properties.get();
        });

        worker.start();
        assertTrue(resultReceived.await(5, TimeUnit.SECONDS));

        RecordedRequest register = takeRequest();
        RecordedRequest binding = takeRequest();
        RecordedRequest socket = takeRequest();
        assertTrue(register.getTarget().endsWith("workers:register"));
        Map<String, Object> registerBody = Jsons.parseObject(
                register.getBody().utf8()
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> registeredProperties =
                (Map<String, Object>) registerBody.get("workerProperties");
        String clientWorkerKey = (String) registeredProperties.get(
                "clientWorkerKey"
        );
        assertEquals(
                UUID.fromString(clientWorkerKey).toString(),
                clientWorkerKey
        );
        assertEquals("android", registeredProperties.get("runtime"));
        assertTrue(binding.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        assertFalse(Jsons.parseObject(binding.getBody().utf8())
                .containsKey("clientWorkerKey"));
        assertEquals(
                clientWorkerKey,
                bindingProperties(binding).get("clientWorkerKey")
        );
        assertEquals(
                "/api/v1/worker-delivery/websocket",
                socket.getTarget()
        );
        assertEquals(application, providerContext.get());
        assertEquals(WORKER_ID, connectionBind.get().workerId());
        assertNotNull(result.get());
        assertEquals("200", result.get().outcomeCode());
        assertEquals(
                "visible",
                Jsons.parseObject(result.get().payload()).get("observed")
        );
    }

    @Test
    public void stopThenStartReusesWorkerIdButAlwaysBindsAgain()
            throws Exception {
        enqueueRegister();
        enqueueBind();
        server.enqueue(webSocketSession(new WebSocketListener() {
        }));
        worker = worker(context -> properties.get());
        worker.start();
        RecordedRequest firstRegister = takeRequest();
        RecordedRequest firstBind = takeRequest();
        takeRequest();
        String clientWorkerKey = (String) bindingProperties(firstBind).get(
                "clientWorkerKey"
        );
        assertTrue(firstRegister.getTarget().endsWith("workers:register"));

        worker.stop();
        await(() -> worker.snapshot().state()
                == WorkerLifecycle.State.STOPPED);
        enqueueBind();
        server.enqueue(webSocketSession(new WebSocketListener() {
        }));
        worker.start();

        RecordedRequest secondBind = takeRequest();
        RecordedRequest secondSocket = takeRequest();
        assertTrue(secondBind.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        assertEquals(
                clientWorkerKey,
                bindingProperties(secondBind).get("clientWorkerKey")
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
        enqueueRegister();
        enqueueBind();
        server.enqueue(webSocketSession(new WebSocketListener() {
        }));
        worker = worker(context -> properties.get());
        worker.start();
        takeRequest();
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
        enqueueBind();
        server.enqueue(webSocketSession(new WebSocketListener() {
        }));
        worker.start();
        RecordedRequest refreshed = takeRequest();
        takeRequest();

        assertTrue(refreshed.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
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
    public void builderRequiresExplicitExecutionResources() {
        assertThrows(
                IllegalStateException.class,
                () -> AndroidWorker.builder(
                                application,
                                URI.create(server.url("/").toString()),
                                WORKER_GROUP_ID
                        )
                        .workerProperties(context -> properties.get())
                        .eventDefinitions(List.of(WorkerEventDefinition.of(
                                "TASK",
                                EVENT_CODE,
                                WorkerEventParameterResolvers.jsonMap(),
                                parameters -> "null"
                        )))
                        .build()
        );
    }

    @Test
    public void onlyOneActiveWorkerPerApplicationAndGroup() throws Exception {
        enqueueRegister();
        enqueueBind();
        server.enqueue(webSocketSession(new WebSocketListener() {
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
    public void processLeaseIsReleasedOnlyAfterStoppingHandlerFinishes()
            throws Exception {
        CountDownLatch entered = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        enqueueRegister();
        enqueueBind();
        server.enqueue(webSocketSession(new WebSocketListener() {
            @Override
            public void onMessage(WebSocket socket, String text) {
                if (codec.decodeWorkerConnectionBind(text) != null) {
                    socket.send(codec.encodeWorkerCommand(
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
                    WorkerLifecycle.State.RUNNING,
                    worker.snapshot().state()
            );
            assertThrows(IllegalStateException.class, duplicate::start);

            release.countDown();
            await(() -> worker.snapshot().state()
                    == WorkerLifecycle.State.STOPPED);

            enqueueBind();
            server.enqueue(webSocketSession(new WebSocketListener() {
            }));
            await(() -> startWhenLeaseIsAvailable(duplicate));
            assertEquals(
                    WorkerLifecycle.State.RUNNING,
                    duplicate.snapshot().state()
            );
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
        return AndroidWorker.builder(
                        application,
                        URI.create(server.url("/").toString()),
                        WORKER_GROUP_ID
                )
                .handlerExecutor(handlerExecutor)
                .workerProperties(provider)
                .eventDefinitions(List.of(WorkerEventDefinition.of(
                        "TASK",
                        EVENT_CODE,
                        WorkerEventParameterResolvers.jsonMap(),
                        handler
                )))
                .requestTimeout(Duration.ofSeconds(2))
                .reconnectPolicy(TextMessageReconnectPolicy.of(
                        20,
                        Duration.ofMillis(20),
                        Duration.ofMillis(100)
                ))
                .build();
    }

    private static boolean startWhenLeaseIsAvailable(AndroidWorker worker) {
        try {
            worker.start();
            return true;
        } catch (IllegalStateException unavailable) {
            return false;
        }
    }

    private void enqueueRegister() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"workerId\":\"" + WORKER_ID + "\"}")
                .build());
    }

    private void enqueueBind() {
        server.enqueue(new MockResponse.Builder()
                .code(200)
                .body("{\"transportType\":\"WEBSOCKET\","
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

    private static WorkerCommand command(String value) {
        return new WorkerCommand(
                "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3",
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
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
}
