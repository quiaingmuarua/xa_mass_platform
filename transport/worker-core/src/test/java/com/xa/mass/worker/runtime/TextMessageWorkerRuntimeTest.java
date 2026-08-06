package com.xa.mass.worker.runtime;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.xa.mass.transport.client.TextMessageClient;
import com.xa.mass.transport.client.WorkerControlClient;
import com.xa.mass.transport.client.WorkerTransportType;
import com.xa.mass.worker.error.WorkerErrorCode;
import com.xa.mass.worker.error.WorkerException;
import com.xa.mass.worker.execution.WorkerEventDefinition;
import com.xa.mass.worker.execution.WorkerEventParameterResolvers;
import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

class TextMessageWorkerRuntimeTest {

    private static final String WORKER_ID =
            "32e4a1d4-38e0-44a2-ac83-d608dd3ba2c1";
    private static final URI ENDPOINT_ONE = URI.create(
            "ws://127.0.0.1:18083/worker"
    );
    private static final URI ENDPOINT_TWO = URI.create(
            "ws://127.0.0.1:18084/worker"
    );

    private TextMessageWorkerRuntime runtime;

    @AfterEach
    void tearDown() {
        if (runtime != null) {
            runtime.close();
        }
    }

    @Test
    void registersPersistsAndBindsImmutableProperties() throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore();
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        Map<String, Object> nested = new LinkedHashMap<>();
        nested.put("tags", new ArrayList<>(List.of("one")));
        Map<String, Object> source = new LinkedHashMap<>();
        source.put("clientWorkerKey", "installation-1");
        source.put("nested", nested);
        runtime = runtime(identity, () -> source, control, networks);

        runtime.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(() -> runtime.snapshot().state()
                == TextMessageWorkerRuntime.State.TRANSPORT_CONNECTED);

        assertEquals(1, control.registerCalls);
        assertEquals(1, control.bindCalls);
        assertEquals(Optional.of(WORKER_ID), identity.loadWorkerId());
        assertEquals(WORKER_ID, runtime.snapshot().workerId());
        assertEquals(ENDPOINT_ONE, runtime.snapshot().endpointUri());
        assertThrows(
                UnsupportedOperationException.class,
                () -> control.registeredProperties.put("new", "value")
        );
        @SuppressWarnings("unchecked")
        Map<String, Object> capturedNested = (Map<String, Object>)
                control.registeredProperties.get("nested");
        assertThrows(
                UnsupportedOperationException.class,
                () -> capturedNested.put("new", "value")
        );
        source.put("afterStart", true);
        assertFalse(control.registeredProperties.containsKey("afterStart"));
    }

    @Test
    void bindUsesConfiguredTextMessageTransportType() throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore(WORKER_ID);
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        runtime = runtime(
                WorkerTransportType.SOCKET,
                identity,
                TextMessageWorkerRuntimeTest::properties,
                control,
                networks
        );

        runtime.start();
        networks.awaitClient(0);

        assertEquals(WorkerTransportType.SOCKET, control.lastTransportType);
    }

    @Test
    void pollingIsNotATextMessageRuntimeType() {
        assertThrows(
                IllegalArgumentException.class,
                () -> runtime(
                        WorkerTransportType.POLLING,
                        new MutableIdentityStore(WORKER_ID),
                        TextMessageWorkerRuntimeTest::properties,
                        new FakeControlClient(),
                        new FakeNetworkFactory()
                )
        );
    }

    @Test
    void everyStartBindsWhileCachedIdentitySkipsRegister() throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore();
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        runtime = runtime(identity, TextMessageWorkerRuntimeTest::properties,
                control, networks);

        runtime.start();
        networks.awaitClient(0).open();
        await(runtime::isConnected);
        runtime.stop();
        assertEquals(TextMessageWorkerRuntime.State.STOPPED,
                runtime.snapshot().state());

        runtime.start();
        networks.awaitClient(1).open();
        await(runtime::isConnected);

        assertEquals(1, control.registerCalls);
        assertEquals(2, control.bindCalls);
        assertEquals(WORKER_ID, runtime.snapshot().workerId());
    }

    @Test
    void noCacheRegistersOnEachSessionButUsesStablePropertiesKey()
            throws Exception {
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        runtime = runtime(
                WorkerIdentityStore.noCache(),
                TextMessageWorkerRuntimeTest::properties,
                control,
                networks
        );

        runtime.start();
        networks.awaitClient(0).open();
        await(runtime::isConnected);
        runtime.stop();
        runtime.start();
        networks.awaitClient(1).open();
        await(runtime::isConnected);

        assertEquals(2, control.registerCalls);
        assertEquals(2, control.bindCalls);
        assertEquals(
                "installation-1",
                control.registeredProperties.get("clientWorkerKey")
        );
    }

    @Test
    void retryableControlFailuresContinueUntilSuccessAndStopCancelsRetry()
            throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore();
        FakeControlClient control = new FakeControlClient();
        control.registerFailures = 2;
        FakeNetworkFactory networks = new FakeNetworkFactory();
        runtime = runtime(identity, TextMessageWorkerRuntimeTest::properties,
                control, networks);

        runtime.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(runtime::isConnected);
        assertEquals(3, control.registerCalls);

        runtime.stop();
        control.registerFailures = Integer.MAX_VALUE;
        identity.workerId = null;
        runtime.start();
        await(() -> control.registerCalls >= 5);
        runtime.stop();
        int afterStop = control.registerCalls;
        Thread.sleep(60);
        assertEquals(afterStop, control.registerCalls);
    }

    @Test
    void websocketReconnectDoesNotRebind() throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore(WORKER_ID);
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        runtime = runtime(identity, TextMessageWorkerRuntimeTest::properties,
                control, networks);

        runtime.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        await(runtime::isConnected);
        client.disconnect();
        await(() -> runtime.snapshot().state()
                == TextMessageWorkerRuntime.State.CONNECTING);
        client.open();
        await(runtime::isConnected);

        assertEquals(0, control.registerCalls);
        assertEquals(1, control.bindCalls);
        assertEquals(1, networks.clients.size());
    }

    @Test
    void failedConnectionBindSendDoesNotReportTransportConnected()
            throws Exception {
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        networks.nextSendAccepted = false;
        runtime = runtime(
                new MutableIdentityStore(WORKER_ID),
                TextMessageWorkerRuntimeTest::properties,
                control,
                networks
        );

        runtime.start();
        FakeTextMessageClient client = networks.awaitClient(0);
        client.open();
        Thread.sleep(30);

        assertFalse(runtime.isConnected());
        assertEquals(
                TextMessageWorkerRuntime.State.CONNECTING,
                runtime.snapshot().state()
        );
    }

    @Test
    void refreshNoopsThenRebindsAndReplacesOnlyForChangedUri()
            throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore(WORKER_ID);
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        AtomicReference<Map<String, Object>> current =
                new AtomicReference<>(properties());
        runtime = runtime(identity, current::get, control, networks);
        runtime.start();
        FakeTextMessageClient first = networks.awaitClient(0);
        first.open();
        await(runtime::isConnected);

        runtime.refreshProperties();
        Thread.sleep(30);
        assertEquals(1, control.bindCalls);

        current.set(Map.of(
                "clientWorkerKey", "installation-1",
                "version", 2
        ));
        runtime.refreshProperties();
        await(() -> control.bindCalls == 2);
        assertEquals(1, networks.clients.size());
        assertFalse(first.closed);

        control.endpoint = ENDPOINT_TWO;
        current.set(Map.of(
                "clientWorkerKey", "installation-1",
                "version", 3
        ));
        runtime.refreshProperties();
        FakeTextMessageClient second = networks.awaitClient(1);
        second.open();
        await(runtime::isConnected);
        assertTrue(first.closed);
        assertEquals(ENDPOINT_TWO, runtime.snapshot().endpointUri());

        control.nextBindFailure = new WorkerException(
                WorkerErrorCode.WORKER_CONTROL_REJECTED,
                "control.bind",
                "rejected",
                null
        );
        current.set(Map.of(
                "clientWorkerKey", "installation-1",
                "version", 4
        ));
        runtime.refreshProperties();
        await(() -> runtime.snapshot().diagnosticMessage() != null);
        assertEquals(2, networks.clients.size());
        assertFalse(second.closed);
        assertEquals(ENDPOINT_TWO, runtime.snapshot().endpointUri());
    }

    @Test
    void refreshRetriesSameSnapshotWhenReplacementCannotStart()
            throws Exception {
        MutableIdentityStore identity = new MutableIdentityStore(WORKER_ID);
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        AtomicReference<Map<String, Object>> current =
                new AtomicReference<>(properties());
        runtime = runtime(identity, current::get, control, networks);
        runtime.start();
        FakeTextMessageClient first = networks.awaitClient(0);
        first.open();
        await(runtime::isConnected);

        control.endpoint = ENDPOINT_TWO;
        current.set(Map.of(
                "clientWorkerKey", "installation-1",
                "version", 2
        ));
        networks.nextStartFailure = new IllegalStateException(
                "cannot start replacement"
        );
        runtime.refreshProperties();

        networks.awaitClient(1);
        await(() -> runtime.snapshot().diagnosticMessage() != null);
        assertFalse(first.closed);
        assertEquals(ENDPOINT_ONE, runtime.snapshot().endpointUri());
        assertEquals(2, control.bindCalls);

        runtime.refreshProperties();
        FakeTextMessageClient replacement = networks.awaitClient(2);
        replacement.open();
        await(runtime::isConnected);

        assertEquals(3, control.bindCalls);
        assertTrue(first.closed);
        assertEquals(ENDPOINT_TWO, runtime.snapshot().endpointUri());
    }

    @Test
    void invalidPropertiesAndCachedWorkerIdFailWithoutNetwork() throws Exception {
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        runtime = runtime(
                new MutableIdentityStore(),
                Map::of,
                control,
                networks
        );

        runtime.start();
        await(() -> runtime.snapshot().state()
                == TextMessageWorkerRuntime.State.ERROR);
        assertEquals(0, control.registerCalls);
        assertTrue(networks.clients.isEmpty());
        runtime.close();

        runtime = runtime(
                new MutableIdentityStore("not-a-uuid"),
                TextMessageWorkerRuntimeTest::properties,
                control,
                networks
        );
        runtime.start();
        await(() -> runtime.snapshot().state()
                == TextMessageWorkerRuntime.State.ERROR);
        assertEquals(0, control.bindCalls);
    }

    @Test
    void listenersRunOnOneLifecycleThread() throws Exception {
        FakeControlClient control = new FakeControlClient();
        FakeNetworkFactory networks = new FakeNetworkFactory();
        runtime = runtime(
                new MutableIdentityStore(),
                TextMessageWorkerRuntimeTest::properties,
                control,
                networks
        );
        List<String> callbackThreads = new CopyOnWriteArrayList<>();
        runtime.addListener(snapshot -> callbackThreads.add(
                Thread.currentThread().getName()
        ));

        runtime.start();
        networks.awaitClient(0).open();
        await(runtime::isConnected);
        await(() -> callbackThreads.size() >= 3);

        assertEquals(1, callbackThreads.stream().distinct().count());
        assertTrue(callbackThreads.get(0).contains(
                "xa-text-message-worker-lifecycle"
        ));
    }

    private TextMessageWorkerRuntime runtime(
            WorkerIdentityStore identity,
            WorkerPropertiesProvider properties,
            FakeControlClient control,
            FakeNetworkFactory networks
    ) {
        return runtime(
                WorkerTransportType.WEBSOCKET,
                identity,
                properties,
                control,
                networks
        );
    }

    private TextMessageWorkerRuntime runtime(
            WorkerTransportType transportType,
            WorkerIdentityStore identity,
            WorkerPropertiesProvider properties,
            FakeControlClient control,
            FakeNetworkFactory networks
    ) {
        return new TextMessageWorkerRuntime(
                "group-1",
                transportType,
                identity,
                properties,
                List.of(WorkerEventDefinition.of(
                        "TASK",
                        "test.observe",
                        WorkerEventParameterResolvers.jsonMap(),
                        parameters -> "null"
                )),
                () -> control,
                networks,
                Duration.ofSeconds(1),
                Duration.ofMillis(10)
        );
    }

    private static Map<String, Object> properties() {
        return Map.of(
                "clientWorkerKey",
                "installation-1",
                "runtime",
                "java"
        );
    }

    private static void await(Check check) throws Exception {
        long deadline = System.nanoTime()
                + TimeUnit.SECONDS.toNanos(3);
        while (System.nanoTime() < deadline) {
            if (check.satisfied()) {
                return;
            }
            Thread.sleep(5);
        }
        assertTrue(check.satisfied(), "condition was not satisfied");
    }

    @FunctionalInterface
    private interface Check {
        boolean satisfied() throws Exception;
    }

    private static final class MutableIdentityStore
            implements WorkerIdentityStore {

        private volatile String workerId;

        private MutableIdentityStore() {
        }

        private MutableIdentityStore(String workerId) {
            this.workerId = workerId;
        }

        @Override
        public Optional<String> loadWorkerId() {
            return Optional.ofNullable(workerId);
        }

        @Override
        public void saveWorkerId(String value) {
            workerId = value;
        }
    }

    private static final class FakeControlClient
            implements WorkerControlClient {

        private volatile int registerCalls;
        private volatile int bindCalls;
        private volatile int registerFailures;
        private volatile URI endpoint = ENDPOINT_ONE;
        private volatile RuntimeException nextBindFailure;
        private volatile Map<String, Object> registeredProperties;
        private volatile WorkerTransportType lastTransportType;

        @Override
        public synchronized String register(
                String workerGroupId,
                Map<String, Object> workerProperties,
                Duration timeout
        ) throws IOException {
            registerCalls++;
            registeredProperties = workerProperties;
            if (registerFailures > 0) {
                registerFailures--;
                throw new IOException("temporarily unavailable");
            }
            return WORKER_ID;
        }

        @Override
        public synchronized URI bind(
                String workerGroupId,
                String workerId,
                WorkerTransportType transportType,
                Map<String, Object> workerProperties,
                Duration timeout
        ) {
            bindCalls++;
            lastTransportType = transportType;
            RuntimeException failure = nextBindFailure;
            nextBindFailure = null;
            if (failure != null) {
                throw failure;
            }
            return endpoint;
        }

        @Override
        public void close() {
        }
    }

    private static final class FakeNetworkFactory
            implements TextMessageWorkerRuntime.NetworkClientFactory {

        private final List<FakeTextMessageClient> clients =
                new CopyOnWriteArrayList<>();
        private volatile RuntimeException nextStartFailure;
        private volatile boolean nextSendAccepted = true;

        @Override
        public TextMessageClient create(URI endpointUri) {
            FakeTextMessageClient client =
                    new FakeTextMessageClient(
                            endpointUri,
                            nextStartFailure,
                            nextSendAccepted
                    );
            nextStartFailure = null;
            nextSendAccepted = true;
            clients.add(client);
            return client;
        }

        FakeTextMessageClient awaitClient(int index) throws Exception {
            await(() -> clients.size() > index
                    && clients.get(index).listener != null);
            return clients.get(index);
        }
    }

    private static final class FakeTextMessageClient
            implements TextMessageClient {

        private final URI endpointUri;
        private final RuntimeException startFailure;
        private final boolean sendAccepted;
        private Listener listener;
        private volatile boolean connected;
        private volatile boolean closed;

        private FakeTextMessageClient(
                URI endpointUri,
                RuntimeException startFailure,
                boolean sendAccepted
        ) {
            this.endpointUri = endpointUri;
            this.startFailure = startFailure;
            this.sendAccepted = sendAccepted;
        }

        @Override
        public void start(Listener value) {
            listener = value;
            if (startFailure != null) {
                throw startFailure;
            }
        }

        @Override
        public boolean send(String message) {
            return connected && !closed && sendAccepted;
        }

        @Override
        public void closeCurrent(CloseReason reason) {
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
            assertNotNull(listener);
            connected = true;
            listener.onOpen();
        }

        void disconnect() {
            connected = false;
            if (listener != null && !closed) {
                listener.onDisconnected();
            }
        }
    }
}
