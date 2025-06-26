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
        EngineResourceRegistry registry = new EngineResourceRegistry();

        // 1. 加载 mock 配置（优先外部文件）
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

        // 2. 生成任务和设备
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        TaskManager taskManager = new TaskManager(scheduler);
        List<Task> allTasks = generateTasks(root, taskManager);
        DeviceManager deviceManager = new DeviceManager();
        List<Token> tokenList = generateDevicesAndTokens(root, deviceManager);

        // 3. 统一注册所有资源
        registerResources(registry, root, allTasks, tokenList, taskManager, deviceManager, scheduler);

        log.info("Created tasks: {}", allTasks.size());
        log.info("Created devices: {}", tokenList.size());

        // 4. 注册审核监听器（使用 EventBusManager）
        EventBusManager.register(new TaskReviewListener(allTasks));

        // 5. 发布审核事件（异步处理）
        for (Task task : allTasks) {
            EventBusManager.postChaosEvent(new TaskReviewEvent.TaskReviewRandomEvent(task.getTid(), 1.0));
        }
        log.info("(Async) Approved tasks: {}", allTasks.stream().filter(t -> t.getStatus() == TaskStatus.READY).count());

        // 6. 获取 worker 并注册任务完成监听器
        TaskAssignWorker assignWorker = registry.get(TaskAssignWorker.class);
        assignWorker.addListener(new TaskCompletionListener() {
            @Override
            public void onTaskCompleted(Task task) {
                log.debug("Task completed: {}", task.getTid());
            }
            @Override
            public void onAllTasksCompleted() {
                log.info("All tasks completed, running pipeline...");
                AssignmentRecordService recordService = registry.get(AssignmentRecordService.class);
                CompletableFuture.runAsync(() -> {
                    List<AssignmentPipelineStep> pipeline = List.of(
                        new AssignmentReportStep(true),
                        new ConflictReportStep(true),
                        new RuleEvaluationStep(true)
                    );
                    for (AssignmentPipelineStep step : pipeline) {
                        if (step.isEnabled()) step.process(recordService);
                    }
                });
            }
        });

        // 7. 启动 worker
        assignWorker.start();
        CompletableFuture<Void> allTasksFuture = assignWorker.submitAll(allTasks);

        // 8. 主流程等待所有任务完成（非阻塞）
        allTasksFuture.thenRun(() -> {
            log.info("All tasks and pipeline processing completed");
            assignWorker.stop();
        });

        log.info("Mock task engine started, tasks are being processed asynchronously");
    }

    /**
     * 统一注册所有核心资源
     */
    private void registerResources(EngineResourceRegistry registry, JsonObject root, List<Task> allTasks, List<Token> tokenList, TaskManager taskManager, DeviceManager deviceManager, SimpleTaskScheduler scheduler) {
        // AssignmentRecordService
        AssignmentRecordService recordService = new AssignmentRecordService();
        registry.register(AssignmentRecordService.class, recordService);

        // RuleManager
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        registry.register(com.xa.mass.engine.rules.RuleManager.class, ruleManager);

        // TaskMsgAssignListener
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        registry.register(com.xa.mass.engine.listener.TaskMsgAssignListener.class, msgAssignListener);

        // TaskDeviceAssignListener
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);
        registry.register(com.xa.mass.engine.listener.TaskDeviceAssignListener.class, deviceAssignListener);

        // TaskAssignWorker
        TaskAssignWorker assignWorker = new TaskAssignWorker(deviceAssignListener);
        registry.register(TaskAssignWorker.class, assignWorker);

        // TaskManager
        registry.register(TaskManager.class, taskManager);
        // DeviceManager
        registry.register(DeviceManager.class, deviceManager);
        // Scheduler
        registry.register(SimpleTaskScheduler.class, scheduler);
    }

    // generateTasks 现在接收 taskManager 参数
    private List<Task> generateTasks(JsonObject root, TaskManager taskManager) {
        List<Task> allTasks = new ArrayList<>();
        if (root.has("tasks")) {
            List<TaskCreateRequestDto> taskDtos = MonkeyTaskGenerator.generateTasks(root.getAsJsonArray("tasks"));
            for (TaskCreateRequestDto dto : taskDtos) {
                Task task = taskManager.createTask(dto);
                task.setRunTaskMinDeviceCnt(dto.getBatchSize());
                task.setBatchSize(dto.getBatchSize());
                log.info("new_task {}", task);
                allTasks.add(task);
            }
        }
        return allTasks;
    }

    // 抽取设备和token生成逻辑
    private List<Token> generateDevicesAndTokens(JsonObject root, DeviceManager deviceManager) {
        List<Token> tokenList = new ArrayList<>();
        if (root.has("devices")) {
            List<Device> devices = MonkeyDeviceGenerator.generateDevices(root.getAsJsonArray("devices").toString(), tokenList);
            for (Device device : devices) {
                deviceManager.addDevice(device);
            }
            for (Token token : tokenList) {
                deviceManager.addToken(token.getDeviceId(), token);
            }
        }
        return tokenList;
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