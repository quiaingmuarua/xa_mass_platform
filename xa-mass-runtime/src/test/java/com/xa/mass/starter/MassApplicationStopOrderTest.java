package com.xa.mass.starter;

import com.xa.mass.gateway.server.MassServerStater;
import com.xa.mass.starter.config.EngineConfig;
import com.xa.mass.starter.config.GatewayConfig;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.*;

/**
 * Verifies shutdown order: Gateway → Engine → Netty (serverStater).
 * Pre-fix the order was: Netty → Engine → Gateway.
 */
class MassApplicationStopOrderTest {

    @Test
    void gatewayStopsBeforeNetty() {
        List<String> order = new ArrayList<>();

        MassGateway gateway = spy(new MassGateway(enabledGateway(), null) {
            @Override public void stop() { order.add("gateway"); }
            @Override public boolean isRunning() { return false; }
        });

        MassServerStater serverStater = mock(MassServerStater.class);
        doAnswer(inv -> { order.add("netty"); return null; }).when(serverStater).stop();
        when(serverStater.isRunning()).thenReturn(false);

        MassApplication app = new MassApplication(null, 0, "/", enabledGateway(), disabledEngine());
        inject(app, "massGateway", gateway);
        inject(app, "serverStater", serverStater);
        setApplicationRunning(app, true);

        app.stop();

        assertEquals(List.of("gateway", "netty"), order,
                "Gateway must stop before Netty to let the dispatcher drain in-flight messages");
    }

    @Test
    void engineStopsBetweenGatewayAndNetty() {
        List<String> order = new ArrayList<>();

        MassGateway gateway = new MassGateway(enabledGateway(), null) {
            @Override public void stop() { order.add("gateway"); }
            @Override public boolean isRunning() { return false; }
        };
        MassEngine engine = new MassEngine(enabledEngine()) {
            @Override public void stop() { order.add("engine"); }
            @Override public boolean isRunning() { return true; }
        };
        inject(engine, "running", true);

        MassServerStater serverStater = mock(MassServerStater.class);
        doAnswer(inv -> { order.add("netty"); return null; }).when(serverStater).stop();

        MassApplication app = new MassApplication(engine, 0, "/", enabledGateway(), enabledEngine());
        inject(app, "massGateway", gateway);
        inject(app, "serverStater", serverStater);
        setApplicationRunning(app, true);

        app.stop();

        assertEquals(List.of("gateway", "engine", "netty"), order,
                "Stop order must be: gateway → engine → netty");
    }

    @Test
    void stopIsIdempotentWhenTriggeredTwice() {
        MassGateway gateway = spy(new MassGateway(enabledGateway(), null) {
            @Override public boolean isRunning() { return false; }
        });
        MassEngine engine = spy(new MassEngine(enabledEngine()) {
            @Override public boolean isRunning() { return true; }
        });
        MassServerStater serverStater = mock(MassServerStater.class);

        MassApplication app = new MassApplication(engine, 0, "/", enabledGateway(), enabledEngine());
        inject(app, "massGateway", gateway);
        inject(app, "serverStater", serverStater);
        setApplicationRunning(app, true);

        app.stop();
        app.stop();

        verify(gateway, times(1)).stop();
        verify(engine, times(1)).stop();
        verify(serverStater, times(1)).stop();
    }

    // ---- helpers ----

    private GatewayConfig enabledGateway() {
        GatewayConfig c = new GatewayConfig(); c.setEnabled(true); return c;
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
