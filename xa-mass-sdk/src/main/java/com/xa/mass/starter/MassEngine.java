package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
import com.xa.mass.base.channel.eventbus.event.task.TaskAssignedEvent;
import com.xa.mass.base.channel.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Worker;
import com.xa.mass.base.model.WorkerContext;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.WorkerManager;
import com.xa.mass.engine.listener.EventListenerRegistry;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.listener.TaskResourceReleaseListener;
import com.xa.mass.engine.listener.TaskWorkerAssignListener;
import com.xa.mass.engine.strategy.TaskWorkerMatchingStrategy;
import com.xa.mass.engine.service.AssignmentRecordService;
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
 *   <li><b>In-process (synchronous):</b> {@link com.xa.mass.engine.TaskManager} exposes
 *       {@code addTask*Listener()} methods backed by
 *       {@link com.xa.mass.engine.TaskEventPublisher}. These fire inline on the calling
 *       thread and are used by the engine internals (assignment, resource release, etc.).</li>
 *   <li><b>EventBus (async-capable):</b> {@code MassEngine.start()} wires a Guava
 *       {@link com.xa.mass.base.channel.eventbus.core.EventBusFacade} that bridges selected
 *       in-process events ({@code TaskCreated}, {@code TaskAssigned}, worker status changes)
 *       to external subscribers. Subscribe to the EventBus when loose coupling or
 *       async delivery is needed; use the in-process listeners when reactions must be
 *       synchronous with the engine operation.</li>
 * </ul>
 */
public class MassEngine {

    private static final Logger logger = LoggerFactory.getLogger(MassEngine.class);

    private final EngineConfig config;
    private boolean running = false;

    private TaskManager taskManager;
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
            taskManager = config.getTaskManager();
            workerManager = config.getWorkerManager();
            recordService = config.getRecordService();
            var ruleManager = config.getRuleManager();
            var msgAssignListener = new SimpleTaskMsgAssignListener(
                    taskManager,
                    workerManager,
                    recordService,
                    taskMsgDispatchListener);
            TaskWorkerMatchingStrategy customStrategy = config.getMatchingStrategy();
            var workerAssignListener = customStrategy != null
                    ? new TaskWorkerAssignListener(customStrategy, workerManager, msgAssignListener, taskManager)
                    : new TaskWorkerAssignListener(ruleManager, workerManager, msgAssignListener, recordService, taskManager);
            assignWorker = new TaskAssignWorker(workerAssignListener, config.getAssignmentRetryDelayMillis());
            assignWorker.start();

            TaskResourceReleaseListener resourceReleaseListener =
                    new TaskResourceReleaseListener(taskManager, workerManager, assignWorker::submit);
            taskManager.addTaskReadyListener(assignWorker::submit);
            taskManager.addTaskDispatchListener(assignWorker::submit);
            taskManager.addTaskMessageAttemptClosedListener(resourceReleaseListener::onTaskMessageAttemptClosed);
            taskManager.addTaskTerminalListener(resourceReleaseListener::onTaskTerminal);
            taskManager.getTasksByStatus(TaskStatus.READY).forEach(assignWorker::submit);

            leaseWatchdog = new LeaseExpireWatchdog(taskManager, config.getLeaseWatchdogIntervalSeconds());
            leaseWatchdog.start();

            eventBus = EventBusFactory.get("guava");
            @SuppressWarnings("unchecked")
            EventBusFacade<Object> bus = (EventBusFacade<Object>) eventBus;
            taskManager.addTaskCreatedListener(task -> bus.post(new TaskCreatedEvent(task, null, null)));
            taskManager.addTaskAssignedListener(task -> bus.post(new TaskAssignedEvent(task, null, null)));
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
            eventBus = null;
            running = false;
            logger.info("MassEngine stopped successfully");
        } catch (Exception e) {
            LogUtils.clearMdc();
            logger.error("Error stopping MassEngine", e);
        }
    }

    public Task createTask(com.xa.mass.engine.model.TaskCreateRequestDto dto) {
        if (taskManager == null) {
            throw new IllegalStateException("MassEngine has not been started; taskManager is unavailable");
        }
        return taskManager.createTask(dto);
    }

    public void addWorker(Worker worker) {
        if (workerManager != null) {
            workerManager.addWorker(worker);
        }
    }

    public void addWorkerContext(WorkerContext workerContext) {
        if (workerManager != null && workerContext != null) {
            workerManager.addWorkerContext(workerContext);
        }
    }

    /**
     * Replays a {@link TaskCreatedEvent} for every existing task to the Guava EventBus.
     *
     * <p>Useful for bootstrapping: newly registered EventBus subscribers can receive
     * a synthetic "created" signal for tasks that were created before they subscribed.
     * Tasks created after engine start already fire this event in real time via
     * {@link com.xa.mass.engine.TaskManager#createTask}.
     */
    @SuppressWarnings("unchecked")
    public void publishTaskEvents() {
        if (taskManager != null && eventBus != null) {
            EventBusFacade<Object> bus = (EventBusFacade<Object>) eventBus;
            List<Task> allTasks = taskManager.getAllTasks();
            for (Task task : allTasks) {
                bus.post(new TaskCreatedEvent(task, null, null));
            }
        }
    }

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public WorkerManager getWorkerManager() {
        return workerManager;
    }

    public AssignmentRecordService getRecordService() {
        return recordService;
    }

    public TaskAssignWorker getAssignWorker() {
        return assignWorker;
    }

    public boolean isRunning() {
        return running;
    }

    public EngineConfig getConfig() {
        return config;
    }
}
