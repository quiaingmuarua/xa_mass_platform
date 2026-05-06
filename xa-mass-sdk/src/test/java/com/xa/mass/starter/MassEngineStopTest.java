package com.xa.mass.starter;

import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.starter.config.EngineConfig;
import org.junit.jupiter.api.Test;

import java.util.List;

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
    void stopDelegatesToAssignWorker() {
        TaskAssignWorker worker = mock(TaskAssignWorker.class);
        MassEngine engine = runningEngineWithWorker(worker);

        engine.stop();

        verify(worker).stop();
        assertFalse(engine.isRunning());
    }

    @Test
    void stopIsIdempotentOnSecondCall() {
        TaskAssignWorker worker = mock(TaskAssignWorker.class);
        MassEngine engine = runningEngineWithWorker(worker);

        engine.stop();
        engine.stop(); // second call should be no-op

        verify(worker, times(1)).stop(); // only once
    }

    @Test
    void startInitializesAssignWorkerWithDefaultConfig() {
        EngineConfig config = new EngineConfig();
        MassEngine engine = new MassEngine(config);

        assertDoesNotThrow(() -> engine.start());
        assertTrue(engine.isRunning());
        assertNotNull(readField(engine, "assignWorker"));
        assertSame(config.getTaskCommandService(), readField(engine, "taskCommands"));

        engine.stop();
    }

    @Test
    void startDoesNotInstallRuntimeEventBusBridgeByDefault() {
        EngineConfig config = new EngineConfig();
        MassEngine engine = new MassEngine(config);

        try {
            engine.start();

            assertEquals(0, listenerCount(config, "taskCreatedListeners"));
            assertEquals(0, listenerCount(config, "taskAssignedListeners"));
            assertEquals(1, listenerCount(config, "taskReadyListeners"));
            assertEquals(1, listenerCount(config, "taskDispatchListeners"));
            assertEquals(1, listenerCount(config, "taskTerminalListeners"));
            assertEquals(1, listenerCount(config, "taskMessageAttemptClosedListeners"));
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

        assertEquals(0, listenerCount(config, "taskReadyListeners"));
        assertEquals(0, listenerCount(config, "taskDispatchListeners"));
        assertEquals(0, listenerCount(config, "taskTerminalListeners"));
        assertEquals(0, listenerCount(config, "taskMessageAttemptClosedListeners"));

        engine.start();
        try {
            assertEquals(1, listenerCount(config, "taskReadyListeners"));
            assertEquals(1, listenerCount(config, "taskDispatchListeners"));
            assertEquals(1, listenerCount(config, "taskTerminalListeners"));
            assertEquals(1, listenerCount(config, "taskMessageAttemptClosedListeners"));
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
            assertEquals(1, listenerCount(config, "taskCreatedListeners"));
            assertEquals(1, listenerCount(config, "taskAssignedListeners"));
        } finally {
            engine.stop();
        }

        assertEquals(0, listenerCount(config, "taskCreatedListeners"));
        assertEquals(0, listenerCount(config, "taskAssignedListeners"));
    }

    // ---- helpers ----

    /** Returns a MassEngine that has been put into running=true via reflection. */
    private MassEngine runningEngine() {
        MassEngine engine = new MassEngine(new EngineConfig());
        setRunning(engine, true);
        return engine;
    }

    private MassEngine runningEngineWithWorker(TaskAssignWorker worker) {
        MassEngine engine = runningEngine();
        setField(engine, "assignWorker", worker);
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

    @SuppressWarnings("unchecked")
    private int listenerCount(EngineConfig config, String fieldName) {
        Object taskManager = config.getTaskManager();
        Object eventPublisher = readField(taskManager, "eventPublisher");
        return ((List<Object>) readField(eventPublisher, fieldName)).size();
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
