package com.xa.mass.starter;

import com.xa.mass.base.enums.task.TaskContract;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskShellCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskDispatchBatchListener;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventListenerRegistrar;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.SimpleTaskDispatchBinder;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.service.AssignmentDiagnosticRecorder;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.util.TraceEventLogger;
import com.xa.mass.engine.watchdog.LeaseExpireWatchdog;
import com.xa.mass.engine.watchdog.RuntimeReadyDispatchPump;
import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.function.Consumer;

/**
 * Assembles and starts the task-scheduling engine for an embedded runtime.
 *
 * <h3>Event Model</h3>
 * <ul>
 *   <li><b>In-process (synchronous):</b> {@link com.xa.mass.engine.TaskEventService}
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
    private static final int STARTUP_READY_TASK_SCAN_LIMIT =
            Integer.getInteger("xa.mass.engine.startupReadyTaskScanLimit", 10_000);

    private final EngineConfig config;
    private boolean running = false;

    private TaskCommandService taskCommands;
    private TaskRuntimeRecoveryPort runtimeRecoveryPort;
    private TaskRuntimeMaintenancePort runtimeMaintenancePort;
    private TaskEventListenerRegistrar eventListeners;
    private TaskEventService taskEvents;
    private TaskAssignWorker assignWorker;
    private LeaseExpireWatchdog leaseWatchdog;
    private RuntimeReadyDispatchPump runtimeReadyDispatchPump;
    private TaskResourceReleaseListener resourceReleaseListener;
    private EngineRuntimeBridge runtimeBridge;
    private Consumer<Task> taskReadyListener;
    private Consumer<Task> taskDispatchSignalListener;
    private Consumer<Task> taskTerminalListener;
    private com.xa.mass.engine.TaskMessageAttemptClosedListener taskMessageAttemptClosedListener;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    public void start() {
        start(null);
    }

    public void start(TaskDispatchBatchListener dispatchBatchListener) {
        LogUtils.clearMdc();
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
            taskCommands = config.getTaskCommandService();
            runtimeRecoveryPort = config.getTaskRuntimeRecoveryPort();
            runtimeMaintenancePort = config.getTaskRuntimeMaintenancePort();
            TaskAssignmentRuntimePort assignmentRuntimePort = config.getTaskAssignmentRuntimePort();
            taskEvents = config.getTaskEventService();
            eventListeners = taskEvents;
            runtimeBridge = config.getRuntimeBridge();
            WorkerManager workerManager = config.getWorkerManager();
            AssignmentDiagnosticRecorder recordService = config.getRecordService();
            var ruleManager = config.getRuleManager();
            TraceEventLogger traceEventLogger = config.getTraceEventLogger();
            var dispatchBinder = new SimpleTaskDispatchBinder(
                    assignmentRuntimePort,
                    workerManager,
                    recordService,
                    dispatchBatchListener,
                    traceEventLogger);
            TaskWorkerMatchingStrategy customStrategy = config.getMatchingStrategy();
            var workerAssignListener = customStrategy != null
                    ? new TaskWorkerAssignListener(customStrategy, workerManager, dispatchBinder, assignmentRuntimePort, taskEvents, traceEventLogger)
                    : new TaskWorkerAssignListener(ruleManager, workerManager, dispatchBinder, recordService, assignmentRuntimePort, taskEvents, traceEventLogger);
            assignWorker = new TaskAssignWorker(workerAssignListener, config.getAssignmentRetryDelayMillis(), traceEventLogger);
            assignWorker.start();
            runtimeReadyDispatchPump = new RuntimeReadyDispatchPump(
                    runtimeRecoveryPort,
                    workerAssignListener::onTaskAssign,
                    config.getRuntimeReadyDispatchIntervalMillis(),
                    STARTUP_READY_TASK_SCAN_LIMIT
            );
            runtimeReadyDispatchPump.start();

            resourceReleaseListener = new TaskResourceReleaseListener(runtimeMaintenancePort, workerManager, traceEventLogger);
            taskReadyListener = task -> {
                if (task != null && task.getExecutionSpec().getContract() == TaskContract.SESSION) {
                    assignWorker.submit(task);
                }
            };
            taskDispatchSignalListener = task -> {
                if (task != null && task.getExecutionSpec().getContract() == TaskContract.SESSION) {
                    assignWorker.submit(task);
                }
            };
            taskMessageAttemptClosedListener = resourceReleaseListener::onTaskMessageAttemptClosed;
            taskTerminalListener = resourceReleaseListener::onTaskTerminal;
            eventListeners.addTaskReadyListener(taskReadyListener);
            eventListeners.addTaskDispatchListener(taskDispatchSignalListener);
            eventListeners.addTaskMessageAttemptClosedListener(taskMessageAttemptClosedListener);
            eventListeners.addTaskTerminalListener(taskTerminalListener);
            recoverRuntimeReadyTasks();

            leaseWatchdog = new LeaseExpireWatchdog(runtimeMaintenancePort, config.getLeaseWatchdogIntervalSeconds());
            leaseWatchdog.start();

            runtimeBridge.start(eventListeners, workerManager);
            running = true;
            logger.info("MassEngine started successfully");
        } catch (Exception e) {
            LogUtils.clearMdc();
            logger.error("Failed to start MassEngine", e);
            throw new RuntimeException("Failed to start MassEngine", e);
        }
    }

    public void stop() {
        LogUtils.clearMdc();
        if (!running) {
            logger.info("MassEngine is not running, skipping stop");
            return;
        }
        logger.info("Stopping MassEngine...");
        try {
            if (eventListeners != null) {
                if (taskReadyListener != null) {
                    eventListeners.removeTaskReadyListener(taskReadyListener);
                }
                if (taskDispatchSignalListener != null) {
                    eventListeners.removeTaskDispatchListener(taskDispatchSignalListener);
                }
            }
            if (eventListeners != null) {
                if (taskMessageAttemptClosedListener != null) {
                    eventListeners.removeTaskMessageAttemptClosedListener(taskMessageAttemptClosedListener);
                }
                if (taskTerminalListener != null) {
                    eventListeners.removeTaskTerminalListener(taskTerminalListener);
                }
            }
            if (runtimeBridge != null) {
                runtimeBridge.stop();
                runtimeBridge = null;
            }
            if (leaseWatchdog != null) {
                leaseWatchdog.stop();
                leaseWatchdog = null;
            }
            if (runtimeReadyDispatchPump != null) {
                runtimeReadyDispatchPump.stop();
                runtimeReadyDispatchPump = null;
            }
            if (assignWorker != null) {
                assignWorker.stop();
                assignWorker = null;
            }
            resourceReleaseListener = null;
            taskReadyListener = null;
            taskDispatchSignalListener = null;
            taskMessageAttemptClosedListener = null;
            taskTerminalListener = null;
            config.shutdownTaskRuntime();
            taskCommands = null;
            runtimeRecoveryPort = null;
            runtimeMaintenancePort = null;
            eventListeners = null;
            taskEvents = null;
            running = false;
            logger.info("MassEngine stopped successfully");
        } catch (Exception e) {
            LogUtils.clearMdc();
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

    private void recoverRuntimeReadyTasks() {
        if (runtimeRecoveryPort == null) {
            return;
        }
        for (Task task : runtimeRecoveryPort.getRuntimeDispatchableTasks(STARTUP_READY_TASK_SCAN_LIMIT)) {
            TaskStatus status = task.getStatus();
            if ((status == TaskStatus.READY || status == TaskStatus.RUNNING)
                    && task.getExecutionSpec().getContract() == TaskContract.SESSION) {
                assignWorker.submit(task);
            }
        }
    }
}


