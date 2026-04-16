package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
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
import com.xa.mass.engine.monkey.MonkeyGenerator;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.util.LogUtils;
import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

public class MassEngine {

    private static final Logger logger = LoggerFactory.getLogger(MassEngine.class);

    private final EngineConfig config;
    private boolean running = false;

    private TaskManager taskManager;
    private WorkerManager workerManager;
    private AssignmentRecordService recordService;
    private TaskAssignWorker assignWorker;
    private EventBusFacade<?> eventBus;
    private WorkerManager.WorkerStatusEventListener workerStatusEventListener;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"workers\": " + MonkeyGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + MonkeyGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    public void start() {
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
            TaskMsgDispatchListener taskMsgDispatchListener = config.getTaskMsgDispatchListener();
            var ruleManager = config.getRuleManager();
            var msgAssignListener = new SimpleTaskMsgAssignListener(
                    taskManager,
                    workerManager,
                    recordService,
                    taskMsgDispatchListener);
            var workerAssignListener = new TaskWorkerAssignListener(
                    ruleManager,
                    workerManager,
                    msgAssignListener,
                    recordService,
                    taskManager);
            assignWorker = new TaskAssignWorker(workerAssignListener);
            assignWorker.start();

            TaskResourceReleaseListener resourceReleaseListener =
                    new TaskResourceReleaseListener(taskManager, workerManager, assignWorker::submit);
            taskManager.addTaskReadyListener(assignWorker::submit);
            taskManager.addTaskDispatchListener(assignWorker::submit);
            taskManager.addTaskMessageFinalListener(resourceReleaseListener::onTaskMessageFinal);
            taskManager.addTaskTerminalListener(resourceReleaseListener::onTaskTerminal);
            taskManager.getTasksByStatus(TaskStatus.READY).forEach(assignWorker::submit);

            eventBus = EventBusFactory.get("guava");
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

    public void publishTaskEvents() {
        if (taskManager != null) {
            EventBusFacade eventBus = EventBusFactory.get("guava");
            List<Task> allTasks = taskManager.getAllTasks();
            for (Task task : allTasks) {
                eventBus.post(new TaskCreatedEvent(task, null, null));
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
