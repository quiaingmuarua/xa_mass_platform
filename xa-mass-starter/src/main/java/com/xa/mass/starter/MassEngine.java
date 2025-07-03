package com.xa.mass.starter;

import com.google.gson.JsonObject;
import com.xa.mass.base.eventbus.core.EventBusFacade;
import com.xa.mass.base.eventbus.core.EventBusFactory;
import com.xa.mass.base.eventbus.task.TaskCreatedEvent;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.EventListenerRegistry;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyGenerator;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

/**
 * 引擎组件
 * 负责任务调度、设备管理等核心业务逻辑的启动与聚合
 */

/**
 * 引擎组件（已简化）
 * 消息处理引擎功能已移至 MassGateway 中
 * 此类保留用于未来扩展其他引擎功能
 */
public class MassEngine {

    private static final Logger logger = LoggerFactory.getLogger(MassEngine.class);

    private final EngineConfig config;
    private boolean running = false;

    // 资源成员变量
    private SimpleTaskScheduler scheduler;
    private TaskManager taskManager;
    private DeviceManager deviceManager;
    private AssignmentRecordService recordService;
    private TaskAssignWorker assignWorker;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    /**
     * 启动引擎（只负责资源/服务/事件注册和生命周期管理）
     */
    public void start() {
        if (!config.isEnabled()) {
            logger.info("MassEngine is disabled, skipping start");
            return;
        }
        logger.info("⚙️ Starting MassEngine with {} worker threads", config.getWorkerThreads());
        try {
            if (config.isMockMode()) {
                // 1. 启动/注册所有资源和服务
                scheduler = config.getScheduler();
                taskManager = config.getTaskManager();
                deviceManager = config.getDeviceManager();
                recordService = config.getRecordService();
                var ruleManager = config.getRuleManager();
                var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
                var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
                assignWorker = new TaskAssignWorker(deviceAssignListener);
                assignWorker.start();
                // 注册事件驱动服务
                EventBusFacade eventBus = EventBusFactory.get("guava");
                EventListenerRegistry.registerDeviceStatusListeners(eventBus, deviceManager);
            }
            running = true;
            logger.info("✅ MassEngine started successfully");
        } catch (Exception e) {
            logger.error("❌ Failed to start MassEngine", e);
            throw new RuntimeException("Failed to start MassEngine", e);
        }
    }


    // 默认 mock 配置
    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"devices\": " + MonkeyGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + MonkeyGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    /**
     * 停止引擎
     */
    public void stop() {
        if (!running) {
            logger.info("MassEngine is not running, skipping stop");
            return;
        }
        logger.info("🛑 Stopping MassEngine...");
        try {
            // TODO: 实现引擎停止逻辑
            running = false;
            logger.info("✅ MassEngine stopped successfully");
        } catch (Exception e) {
            logger.error("❌ Error stopping MassEngine", e);
        }
    }

    /**
     * 创建任务
     */
    public Task createTask(com.xa.mass.engine.model.TaskCreateRequestDto dto) {
        if (taskManager != null) {
            return taskManager.createTask(dto);
        }
        return null;
    }

    /**
     * 添加设备
     */
    public void addDevice(Device device) {
        if (deviceManager != null) {
            deviceManager.addDevice(device);
        }
    }

    /**
     * 添加 Token
     */
    public void addToken(Token token) {
        if (deviceManager != null && token != null) {
            deviceManager.addToken(token.getDeviceId(), token);
        }
    }

    /**
     * 发布所有任务事件
     */
    public void publishTaskEvents() {
        if (taskManager != null) {
            EventBusFacade eventBus = EventBusFactory.get("guava");
            List<Task> allTasks = taskManager.getAllTasks();
            for (Task task : allTasks) {
                eventBus.post(new TaskCreatedEvent(task, null, null));
            }
        }
    }

    public TaskManager getTaskManager() { return taskManager; }
    public DeviceManager getDeviceManager() { return deviceManager; }
    public AssignmentRecordService getRecordService() { return recordService; }
    public TaskAssignWorker getAssignWorker() { return assignWorker; }
    public boolean isRunning() { return running; }
    public EngineConfig getConfig() { return config; }
}