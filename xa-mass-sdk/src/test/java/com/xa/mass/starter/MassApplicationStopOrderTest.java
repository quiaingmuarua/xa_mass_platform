package com.xa.mass.starter;

import com.xa.mass.base.channel.messaging.memory.InMemoryMessageQueue;
import com.xa.mass.engine.command.WorkerCommandDeliveryResult;
import com.xa.mass.engine.command.WorkerCommandDeliveryStatus;
import com.xa.mass.engine.command.WorkerCommandRecord;
import com.xa.mass.engine.command.WorkerCommandStatus;
import com.xa.mass.transport.model.TransportOutboundMessage;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.TransportConfig;
import com.xa.mass.transport.runtime.ManagedTransportAdapter;
import com.xa.mass.transport.runtime.RawWorkerMessageChannel;
import com.xa.mass.transport.runtime.TransportAdapterBootstrap;
import com.xa.mass.transport.runtime.TransportAdapterBootstrapContext;
import com.xa.mass.transport.runtime.TransportRuntimeRegistry;
import com.xa.mass.transport.TransportServer;
import com.xa.mass.transport.WorkerEndpointInspector;
import com.xa.mass.transport.WorkerEndpointRegistry;
import com.xa.mass.transport.WorkerEndpointSnapshot;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.*;

/**
 * Verifies MassApplication transport and engine startup/shutdown order.
 */
class MassApplicationStopOrderTest {

    @Test
    void transportServerStopsBeforeManagedAdapter() throws Exception {
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

        assertEquals(List.of("transport", "websocket"), order,
                "Transport server should stop before optional managed adapter cleanup");
    }

    @Test
    void transportAndAdapterStopBeforeEngine() throws Exception {
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

        assertEquals(List.of("transport", "websocket", "engine"), order,
                "Stop order must be: transport server -> managed adapter -> engine (so buffer drains before engine stops)");
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
    void rawTransportMessageUsesResolvedActiveRouteForWorker() throws Exception {
        RawWorkerMessageChannel channel = mock(RawWorkerMessageChannel.class);
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class,
                withSettings().extraInterfaces(WorkerEndpointInspector.class));
        WorkerEndpointInspector inspector = (WorkerEndpointInspector) endpointRegistry;
        when(registry.resolveWorkerAdapterId("worker-1")).thenReturn("websocket");
        when(inspector.listWorkerEndpoints()).thenReturn(List.of(
                new WorkerEndpointSnapshot("route-public", "worker-1", true, "endpoint-1", "websocket")
        ));
        when(channel.adapterId()).thenReturn("websocket");
        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "rawWorkerMessageChannelsByAdapterId", rawChannels(channel));
        inject(app, "transportRuntimeRegistry", registry);
        inject(app, "endpointRegistry", endpointRegistry);

        assertTrue(app.sendRawTransportMessage("worker-1", "{\"hello\":1}", "trace-1"));
        verify(channel).sendToAdapterRoute("route-public", "{\"hello\":1}", "trace-1");
    }

    @Test
    void rawTransportMessageUsesChannelOwnedByResolvedAdapter() throws Exception {
        RawWorkerMessageChannel first = mock(RawWorkerMessageChannel.class);
        RawWorkerMessageChannel second = mock(RawWorkerMessageChannel.class);
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class,
                withSettings().extraInterfaces(WorkerEndpointInspector.class));
        WorkerEndpointInspector inspector = (WorkerEndpointInspector) endpointRegistry;
        when(registry.resolveWorkerAdapterId("worker-2")).thenReturn("websocket");
        when(inspector.listWorkerEndpoints()).thenReturn(List.of(
                new WorkerEndpointSnapshot("route-private", "worker-2", true, "endpoint-2", "websocket")
        ));
        when(first.adapterId()).thenReturn("socket");
        when(second.adapterId()).thenReturn("websocket");

        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "rawWorkerMessageChannelsByAdapterId", rawChannels(first, second));
        inject(app, "transportRuntimeRegistry", registry);
        inject(app, "endpointRegistry", endpointRegistry);

        assertTrue(app.sendRawTransportMessage("worker-2", "{\"hello\":2}", "trace-2"));
        verify(first, never()).sendToAdapterRoute(anyString(), anyString(), anyString());
        verify(second).sendToAdapterRoute("route-private", "{\"hello\":2}", "trace-2");
    }

    @Test
    void rawTransportMessageReturnsFalseWhenNoUniqueActiveRouteExists() throws Exception {
        RawWorkerMessageChannel first = mock(RawWorkerMessageChannel.class);
        RawWorkerMessageChannel second = mock(RawWorkerMessageChannel.class);
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class,
                withSettings().extraInterfaces(WorkerEndpointInspector.class));
        WorkerEndpointInspector inspector = (WorkerEndpointInspector) endpointRegistry;
        when(registry.resolveWorkerAdapterId("worker-3")).thenReturn("websocket");
        when(inspector.listWorkerEndpoints()).thenReturn(List.of(
                new WorkerEndpointSnapshot("route-a", "worker-3", true, "endpoint-a", "websocket"),
                new WorkerEndpointSnapshot("route-b", "worker-3", true, "endpoint-b", "websocket")
        ));
        when(first.adapterId()).thenReturn("websocket");
        when(second.adapterId()).thenReturn("socket");

        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "rawWorkerMessageChannelsByAdapterId", rawChannels(first, second));
        inject(app, "transportRuntimeRegistry", registry);
        inject(app, "endpointRegistry", endpointRegistry);

        assertFalse(app.sendRawTransportMessage("worker-3", "{\"hello\":3}", "trace-3"));
        verify(first, never()).sendToAdapterRoute(anyString(), anyString(), anyString());
        verify(second, never()).sendToAdapterRoute(anyString(), anyString(), anyString());
    }

    @Test
    void realtimeWorkerCommandDeliveryUsesResolvedRawRouteAndCommandFrame() throws Exception {
        AtomicReference<String> sentJson = new AtomicReference<>();
        RawWorkerMessageChannel channel = mock(RawWorkerMessageChannel.class);
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        WorkerEndpointRegistry endpointRegistry = mock(WorkerEndpointRegistry.class,
                withSettings().extraInterfaces(WorkerEndpointInspector.class));
        WorkerEndpointInspector inspector = (WorkerEndpointInspector) endpointRegistry;
        when(registry.resolveWorkerAdapterId("worker-command-1")).thenReturn("websocket");
        when(inspector.listWorkerEndpoints()).thenReturn(List.of(
                new WorkerEndpointSnapshot("route-command", "worker-command-1", true, "endpoint-1", "websocket")
        ));
        when(channel.adapterId()).thenReturn("websocket");
        doAnswer(inv -> {
            sentJson.set(inv.getArgument(1, String.class));
            return null;
        }).when(channel).sendToAdapterRoute(eq("route-command"), anyString(), anyString());
        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "rawWorkerMessageChannelsByAdapterId", rawChannels(channel));
        inject(app, "transportRuntimeRegistry", registry);
        inject(app, "endpointRegistry", endpointRegistry);

        WorkerCommandDeliveryResult result = invokeRealtimeCommandDelivery(app, command("cmd-realtime", "worker-command-1"));

        assertEquals(WorkerCommandDeliveryStatus.ACCEPTED, result.status());
        verify(channel).sendToAdapterRoute(eq("route-command"), anyString(), eq("worker-command-cmd-realtime"));
        assertTrue(sentJson.get().contains("\"type\":\"worker.command\""));
        assertTrue(sentJson.get().contains("\"commandId\":\"cmd-realtime\""));
        assertTrue(sentJson.get().contains("\"commandType\":\"PING\""));
    }

    @Test
    void realtimeWorkerCommandDeliveryDefersWhenWorkerAdapterHasNoRawCarrier() throws Exception {
        TransportRuntimeRegistry registry = mock(TransportRuntimeRegistry.class);
        when(registry.resolveWorkerAdapterId("worker-polling-1")).thenReturn("polling");
        MassApplication app = new MassApplication(null, enabledWebSocket(), disabledEngine());
        inject(app, "transportRuntimeRegistry", registry);

        WorkerCommandDeliveryResult result = invokeRealtimeCommandDelivery(app, command("cmd-polling", "worker-polling-1"));

        assertEquals(WorkerCommandDeliveryStatus.DEFERRED, result.status());
    }

    @Test
    void startBootstrapsManagedAdapterEvenWhenBundledWebSocketIsDisabled() {
        ManagedTransportAdapter adapter = mock(ManagedTransportAdapter.class);
        when(adapter.isRunning()).thenReturn(true);
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

    @Test
    void startFailureCleansUpInitializedRuntimeResources() {
        List<String> order = new ArrayList<>();
        ManagedTransportAdapter adapter = new RecordingManagedTransportAdapter(order, "adapter-stop");
        FailingTransportServer transportServer = new FailingTransportServer(order);
        TransportConfig transport = disabledTransportWithQueues();
        transport.addSupplementalTransportAdapterBootstrap(new StaticManagedAdapterBootstrap(adapter));
        transport.addSupplementalTransportAdapterBootstrap(new StaticTransportServerBootstrap(transportServer));

        MassApplication app = new MassApplication(null, transport, disabledEngine());

        RuntimeException failure = assertThrows(RuntimeException.class, app::start);

        assertTrue(failure.getMessage().contains("Failed to start Mass Application"));
        assertFalse(app.isRunning());
        assertEquals(List.of("server-start", "server-stop", "adapter-stop"), order);
        assertEquals(false, ((java.util.Map<?, ?>) app.getTransportQueueDetail().get("deliveryDiagnostics")).get("available"));
        assertEquals(false, ((java.util.Map<?, ?>) ((java.util.Map<?, ?>) app.getTransportQueueDetail()
                .get("runtimeExecutors")).get("transport")).get("available"));
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
        c.setOutputQueue(new InMemoryMessageQueue<>("transport-output", TransportOutboundMessage.class));
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

    private Map<String, RawWorkerMessageChannel> rawChannels(RawWorkerMessageChannel... channels) {
        Map<String, RawWorkerMessageChannel> byAdapterId = new LinkedHashMap<>();
        for (RawWorkerMessageChannel channel : channels) {
            byAdapterId.put(channel.adapterId().trim().toLowerCase(java.util.Locale.ROOT), channel);
        }
        return byAdapterId;
    }

    private WorkerCommandDeliveryResult invokeRealtimeCommandDelivery(MassApplication app,
                                                                      WorkerCommandRecord command) {
        try {
            var method = MassApplication.class.getDeclaredMethod("deliverRealtimeWorkerCommand", WorkerCommandRecord.class);
            method.setAccessible(true);
            return (WorkerCommandDeliveryResult) method.invoke(app, command);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private WorkerCommandRecord command(String commandId, String workerId) {
        Instant now = Instant.parse("2026-05-26T00:00:00Z");
        return new WorkerCommandRecord(
                commandId,
                workerId,
                "PING",
                WorkerCommandStatus.REQUESTED,
                "operator",
                "test",
                null,
                1_779_000_000_000L,
                Map.of("mode", "safe"),
                "test",
                0,
                null,
                now,
                now
        );
    }

    private static final class RecordingManagedTransportAdapter implements ManagedTransportAdapter {
        private final List<String> order;
        private final String name;
        private boolean running;

        private RecordingManagedTransportAdapter(List<String> order, String name) {
            this.order = order;
            this.name = name;
        }

        @Override
        public void start() {
            running = true;
        }

        @Override
        public void stop() {
            order.add(name);
            running = false;
        }

        @Override
        public boolean isRunning() {
            return running;
        }
    }

    private static final class StaticManagedAdapterBootstrap
            implements TransportAdapterBootstrap {

        private final ManagedTransportAdapter managedTransportAdapter;

        private StaticManagedAdapterBootstrap(ManagedTransportAdapter managedTransportAdapter) {
            this.managedTransportAdapter = managedTransportAdapter;
        }

        @Override
        public void contribute(TransportAdapterBootstrapContext context) {
            context.registerManagedTransportAdapter(managedTransportAdapter);
        }
    }

    private static final class StaticTransportServerBootstrap
            implements TransportAdapterBootstrap {

        private final TransportServer transportServer;

        private StaticTransportServerBootstrap(TransportServer transportServer) {
            this.transportServer = transportServer;
        }

        @Override
        public void contribute(TransportAdapterBootstrapContext context) {
            context.registerTransportServer(transportServer);
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

    private static final class FailingTransportServer implements TransportServer {
        private final List<String> order;

        private FailingTransportServer(List<String> order) {
            this.order = order;
        }

        @Override
        public void start() {
            order.add("server-start");
            throw new IllegalStateException("server failed");
        }

        @Override
        public void stop() {
            order.add("server-stop");
        }

        @Override
        public boolean isRunning() {
            return false;
        }
    }
}
