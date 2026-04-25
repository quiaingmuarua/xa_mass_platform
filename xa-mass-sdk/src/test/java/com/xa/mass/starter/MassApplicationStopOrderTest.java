package com.xa.mass.starter;

import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.WebSocketConfig;
import com.xa.mass.transport.TransportServer;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Verifies shutdown order: WebSocket adapter -> Engine -> transport server.
 */
class MassApplicationStopOrderTest {

    @Test
    void webSocketAdapterStopsBeforeTransportServer() throws Exception {
        List<String> order = new ArrayList<>();

        MassWebSocketAdapter adapter = spy(new MassWebSocketAdapter(enabledWebSocket(), null) {
            @Override public void stop() { order.add("websocket"); }
            @Override public boolean isRunning() { return false; }
        });

        TransportServer transportServer = mock(TransportServer.class);
        doAnswer(inv -> { order.add("transport"); return null; }).when(transportServer).stop();
        when(transportServer.isRunning()).thenReturn(false);

        MassApplication app = new MassApplication(null, 0, "/", enabledWebSocket(), disabledEngine());
        inject(app, "massWebSocketAdapter", adapter);
        inject(app, "transportServer", transportServer);
        setApplicationRunning(app, true);

        app.stop();

        assertEquals(List.of("websocket", "transport"), order,
                "WebSocket adapter must stop before the transport server to let the dispatcher drain in-flight messages");
    }

    @Test
    void engineStopsBetweenWebSocketAdapterAndTransportServer() throws Exception {
        List<String> order = new ArrayList<>();

        MassWebSocketAdapter adapter = new MassWebSocketAdapter(enabledWebSocket(), null) {
            @Override public void stop() { order.add("websocket"); }
            @Override public boolean isRunning() { return false; }
        };
        MassEngine engine = new MassEngine(enabledEngine()) {
            @Override public void stop() { order.add("engine"); }
            @Override public boolean isRunning() { return true; }
        };
        inject(engine, "running", true);

        TransportServer transportServer = mock(TransportServer.class);
        doAnswer(inv -> { order.add("transport"); return null; }).when(transportServer).stop();

        MassApplication app = new MassApplication(engine, 0, "/", enabledWebSocket(), enabledEngine());
        inject(app, "massWebSocketAdapter", adapter);
        inject(app, "transportServer", transportServer);
        setApplicationRunning(app, true);

        app.stop();

        assertEquals(List.of("websocket", "engine", "transport"), order,
                "Stop order must be: websocket -> engine -> transport server");
    }

    @Test
    void stopIsIdempotentWhenTriggeredTwice() throws Exception {
        MassWebSocketAdapter adapter = spy(new MassWebSocketAdapter(enabledWebSocket(), null) {
            @Override public boolean isRunning() { return false; }
        });
        MassEngine engine = spy(new MassEngine(enabledEngine()) {
            @Override public boolean isRunning() { return true; }
        });
        TransportServer transportServer = mock(TransportServer.class);

        MassApplication app = new MassApplication(engine, 0, "/", enabledWebSocket(), enabledEngine());
        inject(app, "massWebSocketAdapter", adapter);
        inject(app, "transportServer", transportServer);
        setApplicationRunning(app, true);

        app.stop();
        app.stop();

        verify(adapter, times(1)).stop();
        verify(engine, times(1)).stop();
        verify(transportServer, times(1)).stop();
    }

    // ---- helpers ----

    private WebSocketConfig enabledWebSocket() {
        WebSocketConfig c = new WebSocketConfig(); c.setEnabled(true); return c;
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
}
