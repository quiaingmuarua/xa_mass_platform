package com.xa.mass.integration.androidworker;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertNotNull;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import android.app.Application;
import android.content.Context;
import com.xa.mass.transport.client.TextWebSocketClient;
import com.xa.mass.transport.client.okhttp.OkHttpWorkerControlClient;
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
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import mockwebserver3.MockResponse;
import mockwebserver3.MockWebServer;
import mockwebserver3.RecordedRequest;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;

@RunWith(RobolectricTestRunner.class)
@Config(application = Application.class)
public class AndroidWebSocketWorkerPluginTest {

    private static final String WORKER_GROUP_ID = "android-demo-workers";
    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final URI FIRST_ENDPOINT = URI.create(
            "ws://127.0.0.1:18085/api/v1/worker-delivery/websocket"
    );
    private static final URI SECOND_ENDPOINT = URI.create(
            "ws://127.0.0.1:18086/api/v1/worker-delivery/websocket"
    );

    private Application application;
    private MockWebServer server;
    private AndroidWebSocketWorkerPlugin plugin;
    private AndroidWorkerIdentityStore identityStore;
    private AndroidWorkerEndpointCacheStore cacheStore;
    private AndroidDemoStateCapability demoCapability;
    private AtomicReference<Map<String, Object>> properties;
    private FakeNetworkClientFactory networkClients;

    @Before
    public void setUp() throws Exception {
        application = RuntimeEnvironment.getApplication();
        application.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();
        application.getSharedPreferences(
                AndroidDemoStateCapability.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit().clear().commit();
        server = new MockWebServer();
        server.start();
        identityStore = new AndroidWorkerIdentityStore(
                application,
                WORKER_GROUP_ID
        );
        cacheStore = new AndroidWorkerEndpointCacheStore(application);
        demoCapability = new AndroidDemoStateCapability(
                application,
                new AndroidDeviceProperties(application)
        );
        properties = new AtomicReference<>(Map.of(
                "runtime", "android",
                "region", "local"
        ));
        networkClients = new FakeNetworkClientFactory();
    }

    @After
    public void tearDown() throws Exception {
        if (plugin != null) {
            plugin.close();
        }
        server.close();
    }

    @Test
    public void firstStartRegistersBindsExecutesAndCachesEndpoint()
            throws Exception {
        server.enqueue(registerResponse());
        server.enqueue(bindResponse(FIRST_ENDPOINT));
        plugin = plugin(Duration.ofSeconds(2));
        List<AndroidWebSocketWorkerPlugin.State> observedStates =
                new CopyOnWriteArrayList<>();
        plugin.addListener(snapshot ->
                observedStates.add(snapshot.state())
        );

        plugin.start();
        FakeTextWebSocketClient client = awaitClient(0);
        client.open();
        await(() -> plugin.snapshot().state()
                == AndroidWebSocketWorkerPlugin.State
                .TRANSPORT_CONNECTED);

        WorkerDeliveryCodec codec = new WorkerDeliveryCodec();
        WorkerConnectionBind connectionBind =
                codec.decodeWorkerConnectionBind(client.sent().get(0));
        assertEquals(WORKER_ID, connectionBind.workerId());

        demoCapability.incrementCounter();
        client.text(codec.encodeWorkerCommand(new WorkerCommand(
                "4a2f9bc3-c146-4dce-ae85-6f44e94b5cb3",
                WorkerMessageEndpoint.TASK,
                WorkerMessageEndpoint.WORKER,
                AndroidDemoStateCapability.EVENT_CODE,
                System.currentTimeMillis() + 30_000,
                "{}",
                "android-demo-forward"
        )));
        await(() -> client.sent().size() == 2);
        WorkerResult result = codec.decodeWorkerResult(
                client.sent().get(1)
        );
        assertEquals("200", result.outcomeCode());
        assertEquals("android-demo-forward", result.forward());
        assertEquals(
                1L,
                Jsons.parseObject(result.payload()).get("counter")
        );
        assertTrue(observedStates.contains(
                AndroidWebSocketWorkerPlugin.State.REGISTERING
        ));
        assertTrue(observedStates.contains(
                AndroidWebSocketWorkerPlugin.State.TRANSPORT_CONNECTED
        ));

        RecordedRequest register = takeRequest();
        RecordedRequest binding = takeRequest();
        assertTrue(register.getTarget().endsWith("workers:register"));
        String clientKey = (String) Jsons.parseObject(
                register.getBody().utf8()
        ).get("clientWorkerKey");
        assertEquals(
                clientKey,
                java.util.UUID.fromString(clientKey).toString()
        );
        assertTrue(binding.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        assertEquals(
                FIRST_ENDPOINT,
                cacheStore.load().orElseThrow().endpointUri()
        );
    }

    @Test
    public void matchingIdentityPropertiesAndCacheSkipRegisterAndBind()
            throws Exception {
        seedIdentityAndCache(FIRST_ENDPOINT, properties.get());
        plugin = plugin(Duration.ofSeconds(2));

        plugin.start();
        FakeTextWebSocketClient client = awaitClient(0);
        client.open();
        await(() -> plugin.snapshot().state()
                == AndroidWebSocketWorkerPlugin.State
                .TRANSPORT_CONNECTED);

        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
        assertEquals(FIRST_ENDPOINT, plugin.snapshot().endpointUri());

        plugin.stop();
        plugin.start();
        awaitClient(1).open();
        await(() -> plugin.snapshot().state()
                == AndroidWebSocketWorkerPlugin.State
                .TRANSPORT_CONNECTED);
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
        assertEquals(WORKER_ID, plugin.snapshot().workerId());
    }

    @Test
    public void changedPropertiesCauseOneBindAndReplaceCache()
            throws Exception {
        seedIdentityAndCache(
                FIRST_ENDPOINT,
                Collections.singletonMap("runtime", "old")
        );
        server.enqueue(bindResponse(FIRST_ENDPOINT));
        plugin = plugin(Duration.ofSeconds(2));

        plugin.start();
        awaitClient(0);

        RecordedRequest binding = takeRequest();
        assertTrue(binding.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        Map<?, ?> submittedProperties = (Map<?, ?>) Jsons.parseObject(
                binding.getBody().utf8()
        ).get("workerProperties");
        assertEquals("local", submittedProperties.get("region"));
        assertEquals(
                AndroidWorkerPropertiesFingerprint.sha256(properties.get()),
                cacheStore.load().orElseThrow().propertiesSha256()
        );
    }

    @Test
    public void corruptCacheFallsBackToBindWithoutChangingIdentity()
            throws Exception {
        AndroidWorkerIdentityStore.Identity identity =
                seedIdentity();
        application.getSharedPreferences(
                AndroidWorkerIdentityStore.PREFERENCES,
                Context.MODE_PRIVATE
        ).edit()
                .putString("cachedWorkerGroupId", WORKER_GROUP_ID)
                .putString("cachedWorkerId", WORKER_ID)
                .putString("cachedEndpointUri", "not-a-websocket-uri")
                .putString("cachedWorkerPropertiesSha256", "bad")
                .commit();
        server.enqueue(bindResponse(FIRST_ENDPOINT));
        plugin = plugin(Duration.ofSeconds(2));

        plugin.start();
        awaitClient(0);

        assertNotNull(takeRequest());
        assertEquals(
                identity.clientWorkerKey(),
                identityStore.loadOrCreateIdentity().clientWorkerKey()
        );
        assertEquals(
                WORKER_ID,
                identityStore.loadOrCreateIdentity().workerId()
        );
    }

    @Test
    public void thirdUnstableCachedConnectionRefreshesEndpointOnce()
            throws Exception {
        seedIdentityAndCache(FIRST_ENDPOINT, properties.get());
        server.enqueue(bindResponse(SECOND_ENDPOINT));
        plugin = plugin(Duration.ofSeconds(2));

        plugin.start();
        FakeTextWebSocketClient first = awaitClient(0);
        first.disconnect();
        first.disconnect();
        assertNull(server.takeRequest(100, TimeUnit.MILLISECONDS));
        first.disconnect();

        RecordedRequest refresh = takeRequest();
        assertTrue(refresh.getTarget().endsWith(
                "/workers/" + WORKER_ID + ":bind"
        ));
        FakeTextWebSocketClient replacement = awaitClient(1);
        assertEquals(SECOND_ENDPOINT, networkClients.endpoint(1));
        assertTrue(first.closed());
        replacement.open();
        await(() -> plugin.snapshot().state()
                == AndroidWebSocketWorkerPlugin.State
                .TRANSPORT_CONNECTED);

        first.disconnect();
        replacement.disconnect();
        replacement.disconnect();
        replacement.disconnect();
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
    }

    @Test
    public void failedRefreshKeepsOldEndpointAndDoesNotLoop()
            throws Exception {
        seedIdentityAndCache(FIRST_ENDPOINT, properties.get());
        server.enqueue(new MockResponse.Builder().code(503).build());
        plugin = plugin(Duration.ofSeconds(2));

        plugin.start();
        FakeTextWebSocketClient client = awaitClient(0);
        client.disconnect();
        client.disconnect();
        client.disconnect();
        takeRequest();
        await(() -> plugin.snapshot().diagnosticMessage() != null);
        assertEquals(FIRST_ENDPOINT, plugin.snapshot().endpointUri());
        assertEquals(1, networkClients.size());

        client.disconnect();
        client.disconnect();
        client.disconnect();
        assertNull(server.takeRequest(200, TimeUnit.MILLISECONDS));
        assertFalse(plugin.snapshot().state()
                == AndroidWebSocketWorkerPlugin.State.ERROR);
    }

    @Test
    public void refreshedSameEndpointUpdatesCacheWithoutReplacingTransport()
            throws Exception {
        seedIdentityAndCache(FIRST_ENDPOINT, properties.get());
        server.enqueue(bindResponse(FIRST_ENDPOINT));
        plugin = plugin(Duration.ofSeconds(2));

        plugin.start();
        FakeTextWebSocketClient client = awaitClient(0);
        client.disconnect();
        client.disconnect();
        client.disconnect();

        takeRequest();
        await(() -> plugin.snapshot().state()
                == AndroidWebSocketWorkerPlugin.State.CONNECTING);
        assertEquals(1, networkClients.size());
        assertFalse(client.closed());
        assertEquals(
                FIRST_ENDPOINT,
                cacheStore.load().orElseThrow().endpointUri()
        );
    }

    private AndroidWebSocketWorkerPlugin plugin(Duration timeout) {
        return new AndroidWebSocketWorkerPlugin(
                WORKER_GROUP_ID,
                identityStore,
                cacheStore,
                properties::get,
                demoCapability.definitions(),
                () -> new OkHttpWorkerControlClient(
                        URI.create(server.url("/").toString())
                ),
                networkClients,
                timeout
        );
    }

    private AndroidWorkerIdentityStore.Identity seedIdentity() {
        AndroidWorkerIdentityStore.Identity identity =
                identityStore.loadOrCreateIdentity();
        identityStore.persistWorkerId(WORKER_ID);
        return identityStore.loadOrCreateIdentity();
    }

    private void seedIdentityAndCache(
            URI endpoint,
            Map<String, Object> cachedProperties
    ) {
        seedIdentity();
        cacheStore.store(
                WORKER_GROUP_ID,
                WORKER_ID,
                endpoint,
                AndroidWorkerPropertiesFingerprint.sha256(cachedProperties)
        );
    }

    private FakeTextWebSocketClient awaitClient(int index)
            throws Exception {
        await(() -> networkClients.size() > index
                && networkClients.client(index).started());
        return networkClients.client(index);
    }

    private RecordedRequest takeRequest() throws InterruptedException {
        RecordedRequest request = server.takeRequest(5, TimeUnit.SECONDS);
        assertNotNull(request);
        return request;
    }

    private static MockResponse registerResponse() {
        return jsonResponse("{\"workerId\":\"" + WORKER_ID + "\"}");
    }

    private static MockResponse bindResponse(URI endpoint) {
        return jsonResponse(
                "{\"transportType\":\"WEBSOCKET\","
                        + "\"endpointUri\":\"" + endpoint + "\"}"
        );
    }

    private static MockResponse jsonResponse(String body) {
        return new MockResponse.Builder()
                .code(200)
                .body(body)
                .build();
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!check.value() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertTrue("condition was not met", check.value());
    }

    @FunctionalInterface
    private interface Check {
        boolean value();
    }

    private static final class FakeNetworkClientFactory
            implements AndroidWebSocketWorkerPlugin.NetworkClientFactory {

        private final List<URI> endpoints = new CopyOnWriteArrayList<>();
        private final List<FakeTextWebSocketClient> clients =
                new CopyOnWriteArrayList<>();

        @Override
        public TextWebSocketClient create(URI endpointUri) {
            FakeTextWebSocketClient client =
                    new FakeTextWebSocketClient();
            endpoints.add(endpointUri);
            clients.add(client);
            return client;
        }

        int size() {
            return clients.size();
        }

        URI endpoint(int index) {
            return endpoints.get(index);
        }

        FakeTextWebSocketClient client(int index) {
            return clients.get(index);
        }
    }

    private static final class FakeTextWebSocketClient
            implements TextWebSocketClient {

        private final List<String> sent = new CopyOnWriteArrayList<>();
        private volatile Listener listener;
        private volatile boolean connected;
        private volatile boolean closed;

        @Override
        public void start(Listener listener) {
            this.listener = listener;
        }

        @Override
        public boolean send(String message) {
            if (!connected || closed) {
                return false;
            }
            sent.add(message);
            return true;
        }

        @Override
        public void closeCurrent(int code, String reason) {
            disconnect();
        }

        @Override
        public boolean isConnected() {
            return connected && !closed;
        }

        @Override
        public void close() {
            closed = true;
            connected = false;
        }

        void open() {
            connected = true;
            listener.onOpen();
        }

        void text(String message) {
            listener.onText(message);
        }

        void disconnect() {
            connected = false;
            listener.onDisconnected();
        }

        List<String> sent() {
            return sent;
        }

        boolean closed() {
            return closed;
        }

        boolean started() {
            return listener != null;
        }
    }
}
