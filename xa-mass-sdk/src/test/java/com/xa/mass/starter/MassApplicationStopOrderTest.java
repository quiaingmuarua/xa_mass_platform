package com.xa.mass.starter;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.transport.model.WorkerTransportMessage;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.starter.transport.ManagedTransportAdapter;
import com.xa.mass.starter.transport.RawWorkerMessageChannel;
import com.xa.mass.starter.transport.TransportAdapterBootstrap;
import com.xa.mass.starter.transport.TransportAdapterBootstrapContext;
import com.xa.mass.starter.transport.TransportAdapterContribution;
import com.xa.mass.starter.transport.TransportRuntimeRegistry;
import com.xa.mass.transport.TransportServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Verifies shutdown order: WebSocket adapter -> Engine -> transport server.
 */
class MassApplicationStopOrderTest {

    @Test
    void webSocketAdapterStopsBeforeTransportServer() throws Exception {
        List<String> order = new ArrayList<>();

        ManagedTransportAdapter adapter = spy(new RecordingManagedTransportAdapter(order, "websocket"));

        TransportServer transportServer = mock(TransportServer.class);
        doAnswer(inv -> { order.add("transport"); return null; }).when(transportServer).stop();
        when(transportServer.isRunning()).thenReturn(false);

        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "managedTransportAdapters", new ArrayList<>(List.of(adapter)));
        inject(app, "transportServers", new ArrayList<>(List.of(transportServer)));
        setApplicationRunning(app, true);

        app.stop();

        assertEquals(List.of("websocket", "transport"), order,
                "WebSocket adapter must stop before the transport server to let the dispatcher drain in-flight messages");
    }

    @Test
    void engineStopsBetweenWebSocketAdapterAndTransportServer() throws Exception {
        List<String> order = new ArrayList<>();

        ManagedTransportAdapter adapter = new RecordingManagedTransportAdapter(order, "websocket");
        MassEngine engine = new MassEngine(enabledEngine()) {
            @Override public void stop() { order.add("engine"); }
            @Override public boolean isRunning() { return true; }
        };
        inject(engine, "running", true);

        TransportServer transportServer = mock(TransportServer.class);
        doAnswer(inv -> { order.add("transport"); return null; }).when(transportServer).stop();

        MassApplication app = new MassApplication(engine, enabledWebSocket(), enabledEngine());
        inject(app, "managedTransportAdapters", new ArrayList<>(List.of(adapter)));
        inject(app, "transportServers", new ArrayList<>(List.of(transportServer)));
        setApplicationRunning(app, true);

        app.stop();

        assertEquals(List.of("websocket", "engine", "transport"), order,
                "Stop order must be: websocket -> engine -> transport server");
    }

    @Test
    void stopIsIdempotentWhenTriggeredTwice() throws Exception {
        ManagedTransportAdapter adapter = spy(new RecordingManagedTransportAdapter(new ArrayList<>(), "websocket"));
        MassEngine engine = spy(new MassEngine(enabledEngine()) {
            @Override public boolean isRunning() { return true; }
        });
        TransportServer transportServer = mock(TransportServer.class);

        MassApplication app = new MassApplication(engine, enabledWebSocket(), enabledEngine());
        inject(app, "managedTransportAdapters", new ArrayList<>(List.of(adapter)));
        inject(app, "transportServers", new ArrayList<>(List.of(transportServer)));
        setApplicationRunning(app, true);

        app.stop();
        app.stop();

        verify(adapter, times(1)).stop();
        verify(engine, times(1)).stop();
        verify(transportServer, times(1)).stop();
    }

    @Test
    void rawTransportMessageFallsBackToSingleRegisteredChannel() throws Exception {
        RawWorkerMessageChannel channel = mock(RawWorkerMessageChannel.class);
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        when(registry.resolveWorkerAdapterId("worker-1")).thenReturn("websocket");
        when(channel.supports("worker-1", "websocket")).thenReturn(true);
        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "rawWorkerMessageChannels", new ArrayList<>(List.of(channel)));
        inject(app, "transportRuntimeRegistry", registry);

        assertTrue(app.sendRawTransportMessage("worker-1", "{\"hello\":1}", "trace-1"));
        verify(channel).send("worker-1", "{\"hello\":1}", "trace-1");
        verify(channel).supports("worker-1", "websocket");
    }

    @Test
    void rawTransportMessageUsesSupportingChannelWhenMultipleAdaptersExist() throws Exception {
        RawWorkerMessageChannel first = mock(RawWorkerMessageChannel.class);
        RawWorkerMessageChannel second = mock(RawWorkerMessageChannel.class);
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        when(registry.resolveWorkerAdapterId("worker-2")).thenReturn("websocket");
        when(first.supports("worker-2", "websocket")).thenReturn(false);
        when(second.supports("worker-2", "websocket")).thenReturn(true);

        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "rawWorkerMessageChannels", new ArrayList<>(List.of(first, second)));
        inject(app, "transportRuntimeRegistry", registry);

        assertTrue(app.sendRawTransportMessage("worker-2", "{\"hello\":2}", "trace-2"));
        verify(first, never()).send(anyString(), anyString(), anyString());
        verify(second).send("worker-2", "{\"hello\":2}", "trace-2");
    }

    @Test
    void rawTransportMessageReturnsFalseWhenNoChannelAcceptsWorker() throws Exception {
        RawWorkerMessageChannel first = mock(RawWorkerMessageChannel.class);
        RawWorkerMessageChannel second = mock(RawWorkerMessageChannel.class);
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        when(registry.resolveWorkerAdapterId("worker-3")).thenReturn("websocket");
        when(first.supports("worker-3", "websocket")).thenReturn(false);
        when(second.supports("worker-3", "websocket")).thenReturn(false);

        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "rawWorkerMessageChannels", new ArrayList<>(List.of(first, second)));
        inject(app, "transportRuntimeRegistry", registry);

        assertFalse(app.sendRawTransportMessage("worker-3", "{\"hello\":3}", "trace-3"));
        verify(first, never()).send(anyString(), anyString(), anyString());
        verify(second, never()).send(anyString(), anyString(), anyString());
    }

    @Test
    void startBootstrapsManagedAdapterEvenWhenBundledWebSocketIsDisabled() {
        ManagedTransportAdapter adapter = mock(ManagedTransportAdapter.class);
        TransportConfig transport = disabledTransportWithQueues();
        transport.addSupplementalTransportAdapterBootstrap(new StaticManagedAdapterBootstrap(adapter));

        MassApplication app = new MassApplication(null, transport, disabledEngine());

        app.start();
        try {
            verify(adapter).start();
            assertTrue(app.isRunning());
        } finally {
            app.stop();
            verify(adapter).stop();
        }
    }

    @Test
    void adapterBootstrapCanOverrideTransportServerPort() throws Exception {
        TransportConfig transport = disabledTransportWithQueues();
        StaticConfiguredTransportServer transportServer = new StaticConfiguredTransportServer(19093);
        transport.addSupplementalTransportAdapterBootstrap(new StaticTransportServerBootstrap(transportServer));

        transport.getBundledWebSocketAdapterConfig().setServerPort(18080);
        transport.getBundledWebSocketAdapterConfig().setEndpointPath("/default");
        MassApplication app = new MassApplication(null, transport, disabledEngine());

        try {
            app.start();
            assertEquals(19093, transportServer.startedPort());
            assertTrue(app.isRunning());
        } finally {
            app.stop();
            assertTrue(transportServer.wasStopped());
        }
    }

    // ---- helpers ----

    private TransportConfig enabledWebSocket() {
        TransportConfig c = new TransportConfig();
        c.getBundledWebSocketAdapterConfig().setEnabled(true);
        return c;
    }

    private TransportConfig disabledTransportWithQueues() {
        TransportConfig c = new TransportConfig();
        c.getBundledWebSocketAdapterConfig().setEnabled(false);
        c.getBundledWebSocketAdapterConfig().setServerEnabled(false);
        c.setInputQueue(new InMemoryMessageQueue<>("transport-input", String.class));
        c.setOutputQueue(new InMemoryMessageQueue<>("transport-output", WorkerTransportMessage.class));
        return c;
    }

    private EngineConfig enabledEngine() {
        EngineConfig c = new EngineConfig(); c.setEnabled(true); return c;
    }

    private EngineConfig disabledEngine() {
        EngineConfig c = new EngineConfig(); c.setEnabled(false); return c;
    }

    private void inject(Object target, String field, Object value) {
        try {
            Class<?> cls = target.getClass();
            while (cls != null) {
                try {
                    var f = cls.getDeclaredField(field);
                    f.setAccessible(true);
                    f.set(target, value);
                    return;
                } catch (NoSuchFieldException e) {
                    cls = cls.getSuperclass();
                }
            }
            throw new NoSuchFieldException(field + " not found in " + target.getClass());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private void setApplicationRunning(MassApplication app, boolean value) {
        try {
            var field = MassApplication.class.getDeclaredField("running");
            field.setAccessible(true);
            java.util.concurrent.atomic.AtomicBoolean running =
                    (java.util.concurrent.atomic.AtomicBoolean) field.get(app);
            running.set(value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private static final class RecordingManagedTransportAdapter implements ManagedTransportAdapter {
        private final List<String> order;
        private final String name;

        private RecordingManagedTransportAdapter(List<String> order, String name) {
            this.order = order;
            this.name = name;
        }

        @Override
        public void start() {
        }

        @Override
        public void stop() {
            order.add(name);
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }

    private static final class StaticManagedAdapterBootstrap
            implements TransportAdapterBootstrap<WorkerTransportMessage> {

        private final ManagedTransportAdapter managedTransportAdapter;

        private StaticManagedAdapterBootstrap(ManagedTransportAdapter managedTransportAdapter) {
            this.managedTransportAdapter = managedTransportAdapter;
        }

        @Override
        public TransportAdapterContribution create(TransportAdapterBootstrapContext<WorkerTransportMessage> context) {
            return TransportAdapterContribution.builder()
                    .managedTransportAdapter(managedTransportAdapter)
                    .build();
        }
    }

    private static final class StaticTransportServerBootstrap
            implements TransportAdapterBootstrap<WorkerTransportMessage> {

        private final TransportServer transportServer;

        private StaticTransportServerBootstrap(TransportServer transportServer) {
            this.transportServer = transportServer;
        }

        @Override
        public TransportAdapterContribution create(TransportAdapterBootstrapContext<WorkerTransportMessage> context) {
            return TransportAdapterContribution.builder()
                    .transportServer(transportServer)
                    .build();
        }
    }

    private static final class StaticConfiguredTransportServer implements TransportServer {
        private final int configuredPort;
        private boolean running;
        private boolean stopped;

        private StaticConfiguredTransportServer(int configuredPort) {
            this.configuredPort = configuredPort;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            running = false;
            stopped = true;
        }

        @Override
        public boolean isRunning() {
            return running;
        }

        private int startedPort() {
            return configuredPort;
        }

        private boolean wasStopped() {
            return stopped;
        }
    }
}
