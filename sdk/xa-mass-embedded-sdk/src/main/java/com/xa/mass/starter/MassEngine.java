package com.xa.mass.starter;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.engine.EngineRuntimeKernel;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Assembles and starts the task-scheduling engine for an embedded runtime.
 *
 * <h3>Event Model</h3>
 * <ul>
 *   <li><b>In-process (synchronous):</b> {@code TaskEventService}
 *       exposes the runtime listener surface. Its listeners fire inline on the
 *       calling thread and are used by the engine internals
 *       (assignment, resource release, etc.).</li>
 *   <li><b>Optional shell bridge:</b> process-local bridge wiring such as
 *       runtime EventBus forwarding is configured outside the kernel through
 *       {@link EngineRuntimeBridge}. It is not part of the default engine
 *       runtime truth.</li>
 * </ul>
 */
public class MassEngine {

    private static final Logger logger = LoggerFactory.getLogger(MassEngine.class);
    private final EngineConfig config;
    private boolean running = false;

    private TaskCommandService taskCommands;
    private EngineRuntimeKernel runtimeKernel;
    private EngineRuntimeBridge runtimeBridge;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    public void start() {
        start(null);
    }

    public void start(TaskDispatchBatchListener dispatchBatchListener) {
        if (!config.isEnabled()) {
            logger.info("MassEngine is disabled, skipping start");
            return;
        }
        if (running) {
            logger.info("MassEngine is already running, skipping duplicate start");
            return;
        }
        logger.info("Starting MassEngine with {} worker threads", config.getWorkerThreads());
        try {
            runtimeBridge = config.getRuntimeBridge();
            runtimeKernel = new EngineRuntimeKernel(config);
            EngineRuntimeKernel.StartedRuntime startedRuntime = runtimeKernel.start(dispatchBatchListener);
            taskCommands = runtimeKernel.taskCommands();
            runtimeBridge.start(
                    startedRuntime.eventListeners(),
                    startedRuntime.workerResourceRuntime(),
                    startedRuntime.workerDispatchGateRuntime(),
                    startedRuntime.dispatchWakeupCallback());
            config.getWorkerPresenceRuntime().setDispatchWakeupCallback(startedRuntime.dispatchWakeupCallback());
            running = true;
            logger.info("MassEngine started successfully");
        } catch (Exception e) {
            if (runtimeKernel != null) {
                runtimeKernel.stop();
            }
            logger.error("Failed to start MassEngine", e);
            throw new RuntimeException("Failed to start MassEngine", e);
        }
    }

    public void stop() {
        if (!running) {
            logger.info("MassEngine is not running, skipping stop");
            return;
        }
        logger.info("Stopping MassEngine...");
        try {
            if (runtimeBridge != null) {
                runtimeBridge.stop();
                runtimeBridge = null;
            }
            config.getWorkerPresenceRuntime().setDispatchWakeupCallback(null);
            if (runtimeKernel != null) {
                runtimeKernel.stop();
                runtimeKernel = null;
            }
            config.shutdownTaskRuntime();
            taskCommands = null;
            running = false;
            logger.info("MassEngine stopped successfully");
        } catch (Exception e) {
            logger.error("Error stopping MassEngine", e);
        }
    }

    public Task createTaskShell(TaskShellCreateRequestDto dto) {
        if (taskCommands == null) {
            throw new IllegalStateException("MassEngine has not been started; task command service is unavailable");
        }
        return taskCommands.createTaskShell(dto);
    }

    public boolean isRunning() {
        return running;
    }

    public EngineConfig getConfig() {
        return config;
    }

}
