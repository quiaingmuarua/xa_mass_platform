package com.xa.mass.mock.starter;

import com.google.common.eventbus.Subscribe;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskCompletionListener;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.model.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
import com.xa.mass.engine.monkey.report.AssignmentPipelineStep;
import com.xa.mass.engine.monkey.report.AssignmentReportStep;
import com.xa.mass.engine.monkey.report.ConflictReportStep;
import com.xa.mass.engine.monkey.report.RuleEvaluationStep;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.eventbus.enums.task.TaskStatus;
import com.xa.mass.eventbus.event.EventBusManager;
import com.xa.mass.eventbus.event.TaskReviewEvent;
import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;
import com.xa.mass.starter.EngineResourceRegistry;
import com.xa.mass.mock.event.TaskCreatedEvent;
import com.xa.mass.mock.service.AuditService;
import com.xa.mass.mock.service.AssignmentService;
import com.xa.mass.mock.service.PipelineService;
import com.xa.mass.mock.event.AllTasksCompletedEvent;
import com.xa.mass.mock.service.TaskAssignWorkerService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;


/**
 * Mock 全链路任务分配主流程入口，支持 JSON-DSL mock 配置。
 */
@Component
@Profile("mock-engine")
public class MockTaskEngineStarter implements CommandLineRunner {
    private static final Logger log = LoggerFactory.getLogger(MockTaskEngineStarter.class);

    public static TaskManager initTaskManger() {
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        return new TaskManager(scheduler);
    }



    @Override
    public void run(String... args) throws Exception {
        // 1. 启动/注册所有资源和服务
        EngineResourceRegistry registry = new EngineResourceRegistry();
        // 注册 manager、worker、service、listener
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        TaskManager taskManager = new TaskManager(scheduler);
        DeviceManager deviceManager = new DeviceManager();
        AssignmentRecordService recordService = new AssignmentRecordService();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
        TaskAssignWorker assignWorker = new TaskAssignWorker(deviceAssignListener);
        assignWorker.start(); // 常驻后台
        registry.register(TaskManager.class, taskManager);
        registry.register(DeviceManager.class, deviceManager);
        registry.register(AssignmentRecordService.class, recordService);
        registry.register(SimpleTaskScheduler.class, scheduler);
        registry.register(com.xa.mass.engine.rules.RuleManager.class, ruleManager);
        registry.register(com.xa.mass.engine.listener.TaskMsgAssignListener.class, msgAssignListener);
        registry.register(com.xa.mass.engine.listener.TaskDeviceAssignListener.class, deviceAssignListener);
        registry.register(TaskAssignWorker.class, assignWorker);
        // 注册事件驱动服务
        EventBusManager.register(new TaskAssignWorkerService(assignWorker));
        EventBusManager.register(new AuditService());
        EventBusManager.register(new AssignmentService());
        EventBusManager.register(new PipelineService(recordService));

        // 2. 注入 mock 任务和设备
        String configPath = "mock_config.json";
        String jsonDsl;
        try {
            jsonDsl = Files.readString(Path.of(configPath));
            log.info("Loaded mock config from file: {}", configPath);
        } catch (IOException e) {
            log.warn("No external mock config found, using default.");
            jsonDsl = getDefaultMockConfig();
        }
        JsonObject root = JsonParser.parseString(jsonDsl).getAsJsonObject();
        generateTasks(root, taskManager);
        generateDevicesAndTokens(root, deviceManager);

        // 3. 发布任务事件（worker 自动处理）
        List<Task> allTasks = taskManager.getAllTasks();
        for (Task task : allTasks) {
            EventBusManager.post(new TaskCreatedEvent(task));
        }

        log.info("Mock task engine started, tasks are being processed asynchronously");
    }

    // generateTasks 只负责注入
    private void generateTasks(JsonObject root, TaskManager taskManager) {
        if (root.has("tasks")) {
            List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(root.getAsJsonArray("tasks"));
            for (TaskCreateRequestDto dto : taskDtos) {
                Task task = taskManager.createTask(dto);
                task.setRunTaskMinDeviceCnt(dto.getBatchSize());
                task.setBatchSize(dto.getBatchSize());
                log.info("new_task {}", task);
            }
        }
    }

    // generateDevicesAndTokens 只负责注入
    private void generateDevicesAndTokens(JsonObject root, DeviceManager deviceManager) {
        if (root.has("devices")) {
            List<Token> tokenList = new ArrayList<>();
            List<Device> devices = MonkeyDeviceGenerator.generateDevices(root.getAsJsonArray("devices").toString(), tokenList);
            for (Device device : devices) {
                deviceManager.addDevice(device);
            }
            for (Token token : tokenList) {
                deviceManager.addToken(token.getDeviceId(), token);
            }
        }
    }

    // 默认 mock 配置
    private static String getDefaultMockConfig() {
        return "{\n" +
                "  \"devices\": " + MonkeyDeviceGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + MonkeyTaskGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    // Task审核事件监听器
    private static class TaskReviewListener {
        private final List<Task> allTasks;
        public TaskReviewListener(List<Task> allTasks) {
            this.allTasks = allTasks;
        }
        @Subscribe
        public void onTaskReview(TaskReviewEvent.TaskReviewRandomEvent event) {
            // 根据 event.getTaskId() 查找 Task
            for (Task task : allTasks) {
                if (task.getTid().equals(event.getTaskId())) {
                    // 可根据 event.getRandomRate() 实现概率通过，这里简单处理为100%通过
                    task.transitionTo(TaskStatus.READY);
                    break;
                }
            }
        }
    }

} 