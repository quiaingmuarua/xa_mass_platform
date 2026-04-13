package com.xa.mass.starter;

import com.xa.mass.base.channel.eventbus.core.EventBusFacade;
import com.xa.mass.base.channel.eventbus.core.EventBusFactory;
import com.xa.mass.base.channel.eventbus.event.task.TaskCreatedEvent;
import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.EventListenerRegistry;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.listener.TaskMsgDispatchListener;
import com.xa.mass.engine.monkey.MonkeyGenerator;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.starter.config.EngineConfig;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;

/**
 * 寮曟搸缁勪欢
 * 璐熻矗浠诲姟璋冨害銆佽澶囩鐞嗙瓑鏍稿績涓氬姟閫昏緫鐨勫惎鍔ㄤ笌鑱氬悎
 */

/**
 * 寮曟搸缁勪欢锛堝凡绠€鍖栵級
 * 娑堟伅澶勭悊寮曟搸鍔熻兘宸茬Щ鑷?MassGateway 涓?
 * 姝ょ被淇濈暀鐢ㄤ簬鏈潵鎵╁睍鍏朵粬寮曟搸鍔熻兘
 */
public class MassEngine {

    private static final Logger logger = LoggerFactory.getLogger(MassEngine.class);

    private final EngineConfig config;
    private boolean running = false;

    // 璧勬簮鎴愬憳鍙橀噺
    private SimpleTaskScheduler scheduler;
    private TaskManager taskManager;
    private DeviceManager deviceManager;
    private AssignmentRecordService recordService;
    private TaskAssignWorker assignWorker;

    public MassEngine(EngineConfig config) {
        this.config = config;
    }

    // 榛樿 mock 閰嶇疆
    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"devices\": " + MonkeyGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + MonkeyGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    /**
     * 鍚姩寮曟搸锛堝彧璐熻矗璧勬簮/鏈嶅姟/浜嬩欢娉ㄥ唽鍜岀敓鍛藉懆鏈熺鐞嗭級
     */
    public void start() {
        if (!config.isEnabled()) {
            logger.info("MassEngine is disabled, skipping start");
            return;
        }
        if (running) {
            logger.info("MassEngine is already running, skipping duplicate start");
            return;
        }
        logger.info("鈿欙笍 Starting MassEngine with {} worker threads", config.getWorkerThreads());
        try {
            // 1. 鍚姩/娉ㄥ唽鎵€鏈夎祫婧愬拰鏈嶅姟
            scheduler = config.getScheduler();
            taskManager = config.getTaskManager();
            deviceManager = config.getDeviceManager();
            recordService = config.getRecordService();
            TaskMsgDispatchListener taskMsgDispatchListener = config.getTaskMsgDispatchListener();
            var ruleManager = config.getRuleManager();
            var msgAssignListener = new SimpleTaskMsgAssignListener(taskManager, deviceManager, recordService, taskMsgDispatchListener);
            var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
            assignWorker = new TaskAssignWorker(deviceAssignListener);
            assignWorker.start();
            taskManager.addTaskReadyListener(assignWorker::submit);
            taskManager.getTasksByStatus(TaskStatus.READY).forEach(assignWorker::submit);
            // 娉ㄥ唽浜嬩欢椹卞姩鏈嶅姟
            EventBusFacade eventBus = EventBusFactory.get("guava");
            EventListenerRegistry.registerDeviceStatusListeners(eventBus, deviceManager);
            running = true;
            logger.info("鉁?MassEngine started successfully");
        } catch (Exception e) {
            logger.error("鉂?Failed to start MassEngine", e);
            throw new RuntimeException("Failed to start MassEngine", e);
        }
    }

    /**
     * 鍋滄寮曟搸
     */
    public void stop() {
        if (!running) {
            logger.info("MassEngine is not running, skipping stop");
            return;
        }
        logger.info("馃洃 Stopping MassEngine...");
        try {
            if (assignWorker != null) {
                assignWorker.stop();
            }
            running = false;
            logger.info("鉁?MassEngine stopped successfully");
        } catch (Exception e) {
            logger.error("鉂?Error stopping MassEngine", e);
        }
    }

    /**
     * 鍒涘缓浠诲姟
     */
    public Task createTask(com.xa.mass.engine.model.TaskCreateRequestDto dto) {
        if (taskManager == null) {
            throw new IllegalStateException("MassEngine has not been started; taskManager is unavailable");
        }
        return taskManager.createTask(dto);
    }

    /**
     * 娣诲姞璁惧
     */
    public void addDevice(Device device) {
        if (deviceManager != null) {
            deviceManager.addDevice(device);
        }
    }

    /**
     * 娣诲姞 Token
     */
    public void addToken(Token token) {
        if (deviceManager != null && token != null) {
            deviceManager.addToken(token.getDeviceId(), token);
        }
    }

    /**
     * 鍙戝竷鎵€鏈変换鍔′簨浠?
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

    public TaskManager getTaskManager() {
        return taskManager;
    }

    public DeviceManager getDeviceManager() {
        return deviceManager;
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
