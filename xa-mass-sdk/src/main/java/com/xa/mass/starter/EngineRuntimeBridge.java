package com.xa.mass.starter;

import com.xa.mass.engine.TaskEventListenerRegistrar;
import com.xa.mass.engine.WorkerManager;

/**
 * Optional shell-side bridge installed around the engine runtime.
 *
 * <p>This seam is intentionally outside the engine kernel. Use it for
 * process-local shell wiring such as bridging selected engine events into a
 * local event bus. Do not treat it as distributed runtime truth.
 */
public interface EngineRuntimeBridge {

    void start(TaskEventListenerRegistrar eventListeners, WorkerManager workerManager);

    void stop();

    static EngineRuntimeBridge noop() {
        return NoopEngineRuntimeBridge.INSTANCE;
    }

    final class NoopEngineRuntimeBridge implements EngineRuntimeBridge {
        private static final NoopEngineRuntimeBridge INSTANCE = new NoopEngineRuntimeBridge();

        private NoopEngineRuntimeBridge() {
        }

        @Override
        public void start(TaskEventListenerRegistrar eventListeners, WorkerManager workerManager) {
        }

        @Override
        public void stop() {
        }
    }
}
