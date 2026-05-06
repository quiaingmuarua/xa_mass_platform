package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
import com.xa.mass.base.channel.eventbus.event.task.TaskAssignedEvent;
import com.xa.mass.base.channel.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskCreateRequestDto;
import com.xa.mass.base.runtime.dispatch.TaskMsgDispatchListener;
import com.xa.mass.engine.TaskAssignmentRuntimePort;
import com.xa.mass.engine.TaskCommandService;
import com.xa.mass.engine.TaskEventListenerRegistrar;
import com.xa.mass.engine.TaskEventService;
import com.xa.mass.engine.TaskRuntimeRecoveryPort;
import com.xa.mass.engine.TaskRuntimeMaintenancePort;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.EventListenerRegistry;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.engine.watchdog.LeaseExpireWatchdog;
import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * Assembles and starts the task-scheduling engine for an embedded runtime.
 *
 * <h3>Two-tier event model</h3>
 * <ul>
 *   <li><b>In-process (synchronous):</b> {@link com.xa.mass.engine.TaskEventService}
 *       exposes the runtime listener surface. Its listeners fire inline on the
 *       calling thread and are used by the engine internals
 *       (assignment, resource release, etc.).</li>
 *   <li><b>EventBus (async-capable):</b> {@code MassEngine.start()} wires a runtime
 *       {@link com.xa.mass.base.channel.eventbus.core.EventBusFacade} that bridges selected
 *       in-process events ({@code TaskCreated}, {@code TaskAssigned}, worker status changes)
 *       to external subscribers. Subscribe to the EventBus when loose coupling or
 *       async delivery is needed; use the in-process listeners when reactions must be
 *       synchronous with the engine operation.</li>
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
    private WorkerManager workerManager;
    private AssignmentRecordService recordService;
    private TaskAssignWorker assignWorker;
    private LeaseExpireWatchdog leaseWatchdog;
    private EventBusFacade<?> eventBus;
    private WorkerManager.WorkerStatusEventListener workerStatusEventListener;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    public void start() {
        start(null);
    }

    public void start(TaskMsgDispatchListener taskMsgDispatchListener) {
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
            workerManager = config.getWorkerManager();
            recordService = config.getRecordService();
            var ruleManager = config.getRuleManager();
            var msgAssignListener = new SimpleTaskMsgAssignListener(
                    assignmentRuntimePort,
                    workerManager,
                    recordService,
                    taskMsgDispatchListener);
            TaskWorkerMatchingStrategy customStrategy = config.getMatchingStrategy();
            var workerAssignListener = customStrategy != null
                    ? new TaskWorkerAssignListener(customStrategy, workerManager, msgAssignListener, assignmentRuntimePort, taskEvents)
                    : new TaskWorkerAssignListener(ruleManager, workerManager, msgAssignListener, recordService, assignmentRuntimePort, taskEvents);
            assignWorker = new TaskAssignWorker(workerAssignListener, config.getAssignmentRetryDelayMillis());
            assignWorker.start();

            TaskResourceReleaseListener resourceReleaseListener =
                    new TaskResourceReleaseListener(runtimeMaintenancePort, workerManager);
            eventListeners.addTaskReadyListener(assignWorker::submit);
            eventListeners.addTaskDispatchListener(assignWorker::submit);
            eventListeners.addTaskMessageAttemptClosedListener(resourceReleaseListener::onTaskMessageAttemptClosed);
            eventListeners.addTaskTerminalListener(resourceReleaseListener::onTaskTerminal);
            recoverRuntimeReadyTasks();

            leaseWatchdog = new LeaseExpireWatchdog(runtimeMaintenancePort, config.getLeaseWatchdogIntervalSeconds());
            leaseWatchdog.start();

            eventBus = EventBusFactory.get("runtime");
            @SuppressWarnings("unchecked")
            EventBusFacade<Object> bus = (EventBusFacade<Object>) eventBus;
            eventListeners.addTaskCreatedListener(task -> bus.post(new TaskCreatedEvent(task, null, null)));
            eventListeners.addTaskAssignedListener(task -> bus.post(new TaskAssignedEvent(task, null, null)));
            workerStatusEventListener = EventListenerRegistry.registerWorkerStatusListeners(eventBus, workerManager);
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
            if (eventBus != null && workerStatusEventListener != null) {
                try {
                    eventBus.unregister(workerStatusEventListener);
                } catch (RuntimeException e) {
                    logger.warn("Failed to unregister worker status event listener cleanly: {}", e.getMessage());
                } finally {
                    workerStatusEventListener = null;
                }
            }
            if (leaseWatchdog != null) {
                leaseWatchdog.stop();
                leaseWatchdog = null;
            }
            if (assignWorker != null) {
                assignWorker.stop();
                assignWorker = null;
            }
            config.shutdownTaskRuntime();
            taskCommands = null;
            runtimeRecoveryPort = null;
            runtimeMaintenancePort = null;
            eventListeners = null;
            taskEvents = null;
            eventBus = null;
            running = false;
            logger.info("MassEngine stopped successfully");
        } catch (Exception e) {
            LogUtils.clearMdc();
            logger.error("Error stopping MassEngine", e);
        }
    }

    public Task createTask(TaskCreateRequestDto dto) {
        if (taskCommands == null) {
            throw new IllegalStateException("MassEngine has not been started; task command service is unavailable");
        }
        return taskCommands.createTask(dto);
    }

    /**
     * No-op. Full-table replay of TaskCreatedEvent is not supported at scale.
     * Subscribers that need historical state should query storage directly via TaskQueryService.
     */
    public void publishTaskEvents() {
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
            if (status == TaskStatus.READY || status == TaskStatus.RUNNING) {
                assignWorker.submit(task);
            }
        }
    }
}

