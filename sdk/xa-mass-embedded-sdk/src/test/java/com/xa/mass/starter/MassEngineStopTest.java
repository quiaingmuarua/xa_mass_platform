package com.xa.mass.starter;

import com.xa.mass.engine.EngineRuntimeKernel;
import com.xa.mass.engine.TaskEventListenerSnapshot;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.starter.config.EngineConfig;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

class MassEngineStopTest {

    @Test
    void stopWhenNotRunningIsIdempotent() {
        MassEngine engine = new MassEngine(new EngineConfig());
        assertFalse(engine.isRunning());
        assertDoesNotThrow(engine::stop);
        assertFalse(engine.isRunning());
    }

    @Test
    void stopSetsRunningFalse() {
        MassEngine engine = runningEngine();
        assertTrue(engine.isRunning());

        engine.stop();

        assertFalse(engine.isRunning());
    }

    @Test
    void stopDelegatesToRuntimeKernel() {
        EngineRuntimeKernel runtimeKernel = mock(EngineRuntimeKernel.class);
        MassEngine engine = runningEngineWithRuntimeKernel(runtimeKernel);

        engine.stop();

        verify(runtimeKernel).stop();
        assertFalse(engine.isRunning());
    }

    @Test
    void stopIsIdempotentOnSecondCall() {
        EngineRuntimeKernel runtimeKernel = mock(EngineRuntimeKernel.class);
        MassEngine engine = runningEngineWithRuntimeKernel(runtimeKernel);

        engine.stop();
        engine.stop(); // second call should be no-op

        verify(runtimeKernel, times(1)).stop(); // only once
    }

    @Test
    void startInitializesAssignWorkerWithDefaultConfig() {
        EngineConfig config = new EngineConfig();
        MassEngine engine = new MassEngine(config);

        assertDoesNotThrow(() -> engine.start());
        assertTrue(engine.isRunning());
        assertNotNull(readField(engine, "runtimeKernel"));
        assertNotNull(readField(engine, "taskCommands"));

        engine.stop();
    }

    @Test
    void startDoesNotInstallRuntimeEventBusBridgeByDefault() {
        EngineConfig config = new EngineConfig();
        MassEngine engine = new MassEngine(config);

        try {
            engine.start();

            assertEquals(1, listenerSnapshot(config).taskCreatedListeners());
            assertEquals(1, listenerSnapshot(config).taskAssignedListeners());
            assertEquals(2, listenerSnapshot(config).taskReadyListeners());
            assertEquals(2, listenerSnapshot(config).taskDispatchListeners());
            assertEquals(2, listenerSnapshot(config).taskTerminalListeners());
            assertEquals(1, listenerSnapshot(config).taskWorkAttemptClosedListeners());
        } finally {
            engine.stop();
        }
    }

    @Test
    void stopRemovesEngineRuntimeListenersSoRestartDoesNotAccumulate() {
        EngineConfig config = new EngineConfig();
        MassEngine engine = new MassEngine(config);

        engine.start();
        engine.stop();

        assertEquals(0, listenerSnapshot(config).taskReadyListeners());
        assertEquals(0, listenerSnapshot(config).taskDispatchListeners());
        assertEquals(0, listenerSnapshot(config).taskTerminalListeners());
        assertEquals(0, listenerSnapshot(config).taskWorkAttemptClosedListeners());

        engine.start();
        try {
            assertEquals(2, listenerSnapshot(config).taskReadyListeners());
            assertEquals(2, listenerSnapshot(config).taskDispatchListeners());
            assertEquals(2, listenerSnapshot(config).taskTerminalListeners());
            assertEquals(1, listenerSnapshot(config).taskWorkAttemptClosedListeners());
        } finally {
            engine.stop();
        }
    }

    @Test
    void explicitRuntimeEventBusBridgeAddsAndRemovesShellBridgeListeners() {
        EngineConfig config = new EngineConfig();
        config.setRuntimeBridge(RuntimeEventBusEngineBridge.runtimeBus());
        MassEngine engine = new MassEngine(config);

        engine.start();
        try {
            assertEquals(2, listenerSnapshot(config).taskCreatedListeners());
            assertEquals(2, listenerSnapshot(config).taskAssignedListeners());
        } finally {
            engine.stop();
        }

        assertEquals(0, listenerSnapshot(config).taskCreatedListeners());
        assertEquals(0, listenerSnapshot(config).taskAssignedListeners());
    }

    // ---- helpers ----

    /** Returns a MassEngine that has been put into running=true via reflection. */
    private MassEngine runningEngine() {
        MassEngine engine = new MassEngine(new EngineConfig());
        setRunning(engine, true);
        return engine;
    }

    private MassEngine runningEngineWithRuntimeKernel(EngineRuntimeKernel runtimeKernel) {
        MassEngine engine = runningEngine();
        setField(engine, "runtimeKernel", runtimeKernel);
        return engine;
    }

    private void setRunning(MassEngine engine, boolean value) {
        setField(engine, "running", value);
    }

    private Object readField(Object target, String fieldName) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            return field.get(target);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private TaskEventListenerSnapshot listenerSnapshot(EngineConfig config) {
        TaskEventService service = (TaskEventService) readField(config, "taskEventService");
        if (service == null) {
            return new TaskEventListenerSnapshot(0, 0, 0, 0, 0, 0, 0);
        }
        return service.listenerSnapshot();
    }

    private void setField(Object target, String fieldName, Object value) {
        try {
            var field = target.getClass().getDeclaredField(fieldName);
            field.setAccessible(true);
            field.set(target, value);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
}
