package com.xa.mass.integration.androidworker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import android.os.Looper;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol
        .WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol
        .WorkerDeliveryProtocol.WorkerConnectionBind;
import com.xa.mass.workerdelivery.protocol
        .WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol
        .WorkerDeliveryProtocol.WorkerResult;
import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import okhttp3.WebSocket;
import okhttp3.WebSocketListener;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.Shadows;
import org.robolectric.shadows.ShadowSystemClock;

@RunWith(RobolectricTestRunner.class)
public class AndroidWorkerDemoControllerTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";

    private Application application;
    private MockWebServer server;
    private AndroidWorkerDemoController controller;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                "android-worker-demo",
                Context.MODE_PRIVATE
        ).edit().clear().commit();
        server = new MockWebServer();
        server.start();
    }

    @After
    public void tearDown() throws Exception {
        if (controller != null) {
            controller.close();
        }
        server.close();
    }

    @Test
    public void registersBindsAndExecutesAndroidStateCommand()
            throws Exception {
        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        AtomicReference<WorkerConnectionBind> bind =
                new AtomicReference<>();
        AtomicReference<WorkerResult> result =
                new AtomicReference<>();
        CountDownLatch resultReceived = new CountDownLatch(1);
        WorkerCommand command = new WorkerCommand(
                "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3",
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
                AndroidWorkerDemoController.EVENT_CODE,
                System.currentTimeMillis() + 30_000,
                "{}",
                "android-demo-forward"
        );
        WebSocketListener serverListener = new WebSocketListener() {
            @Override
            public void onMessage(WebSocket webSocket, String text) {
                if (bind.get() == null) {
                    bind.set(codec.decodeWorkerConnectionBind(text));
                    webSocket.send(codec.encodeWorkerCommand(command));
                    return;
                }
                result.set(codec.decodeWorkerResult(text));
                resultReceived.countDown();
            }
        };

        server.enqueue(jsonResponse(
                "{\"workerId\":\"" + WORKER_ID + "\"}"
        ));
        server.enqueue(jsonResponse(
                "{\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"" + socketUri() + "\"}"
        ));
        server.enqueue(new MockResponse.Builder()
                .webSocketUpgrade(serverListener)
                .build());

        List<AndroidWorkerDemoController.Snapshot> snapshots =
                new CopyOnWriteArrayList<>();
        controller = controller(snapshots);
        controller.incrementCounter();
        controller.start();

        assertTrue(resultReceived.await(5, TimeUnit.SECONDS));
        assertNotNull(bind.get());
        assertEquals(WORKER_ID, bind.get().workerId());
        assertNotNull(result.get());
        assertEquals("200", result.get().outcomeCode());
        assertEquals(
                "android-demo-forward",
                result.get().forward()
        );
        Map<String, Object> payload = Jsons.parseObject(
                result.get().payload()
        );
        assertEquals(1L, payload.get("counter"));
        assertEquals(
                application.getPackageName(),
                payload.get("packageName")
        );
        await(() -> snapshots.stream().anyMatch(snapshot ->
                snapshot.state()
                        == AndroidWorkerDemoController.State
                        .TRANSPORT_CONNECTED
                        && snapshot.processedCommands() == 1
        ));

        RecordedRequest register = server.takeRequest(
                1,
                TimeUnit.SECONDS
        );
        RecordedRequest binding = server.takeRequest(
                1,
                TimeUnit.SECONDS
        );
        assertNotNull(register);
        assertNotNull(binding);
        assertEquals(
                "/api/v1/worker-groups/android-demo-workers/"
                        + "workers:register",
                register.getTarget()
        );
        assertTrue(binding.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        Map<String, Object> bindingBody = Jsons.parseObject(
                binding.getBody().utf8()
        );
        assertEquals("WEBSOCKET", bindingBody.get("transportType"));
        Map<?, ?> properties = (Map<?, ?>) bindingBody.get(
                "workerProperties"
        );
        assertEquals("android", properties.get("runtime"));
    }

    @Test
    public void persistedWorkerIdSkipsRegisterAndStopIsTerminalForSession()
            throws Exception {
        AndroidWorkerIdentityStore store =
                new AndroidWorkerIdentityStore(
                        application,
                        AndroidWorkerDemoController.WORKER_GROUP_ID
                );
        store.loadOrCreateIdentity();
        store.persistWorkerId(WORKER_ID);
        server.enqueue(jsonResponse(
                "{\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"" + socketUri() + "\"}"
        ));
        server.enqueue(new MockResponse.Builder()
                .webSocketUpgrade(new WebSocketListener() {})
                .build());

        List<AndroidWorkerDemoController.Snapshot> snapshots =
                new CopyOnWriteArrayList<>();
        controller = controller(snapshots);
        controller.start();
        await(() -> snapshots.stream().anyMatch(snapshot ->
                snapshot.state()
                        == AndroidWorkerDemoController.State
                        .TRANSPORT_CONNECTED
        ));

        RecordedRequest first = server.takeRequest(
                1,
                TimeUnit.SECONDS
        );
        assertNotNull(first);
        assertTrue(first.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        controller.stop();
        assertEquals(
                AndroidWorkerDemoController.State.STOPPED,
                controller.snapshot().state()
        );
    }

    private AndroidWorkerDemoController controller(
            List<AndroidWorkerDemoController.Snapshot> snapshots
    ) {
        return new AndroidWorkerDemoController(
                application,
                URI.create(server.url("/").toString()),
                snapshots::add,
                Duration.ofSeconds(2),
                Duration.ofMillis(20),
                10
        );
    }

    private String socketUri() {
        return server.url("/api/v1/worker-delivery/websocket")
                .toString()
                .replaceFirst("^http", "ws");
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .body(body)
                .build();
    }

    private void await(Check check) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(5);
        while (!check.value() && System.nanoTime() < deadline) {
            ShadowSystemClock.advanceBy(Duration.ofMillis(20));
            Shadows.shadowOf(Looper.getMainLooper()).idle();
            Thread.sleep(10);
        }
        Shadows.shadowOf(Looper.getMainLooper()).idle();
        AndroidWorkerDemoController.Snapshot snapshot =
                controller == null ? null : controller.snapshot();
        assertTrue(
                snapshot == null
                        ? "condition was not met"
                        : "condition was not met; state="
                        + snapshot.state()
                        + " error="
                        + snapshot.errorMessage()
                        + " workerId="
                        + snapshot.workerId(),
                check.value()
        );
    }

    @FunctionalInterface
    private interface Check {
        boolean value();
    }
}
