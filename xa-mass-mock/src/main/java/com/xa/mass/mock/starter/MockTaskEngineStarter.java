package com.xa.mass.mock.starter;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.engine.monkey.MonkeyDeviceGenerator;
import com.xa.mass.engine.monkey.MonkeyTaskGenerator;
import com.xa.mass.engine.report.ConflictReportStep;
import com.xa.mass.engine.report.RuleEvaluationStep;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.listener.TaskDeviceAssignListener;
import com.xa.mass.engine.listener.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;

import com.xa.mass.engine.listener.TaskAssignWorker;
import com.xa.mass.engine.listener.TaskCompletionListener;
import com.xa.mass.engine.report.AssignmentPipelineStep;
import com.xa.mass.engine.report.AssignmentReportStep;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import com.google.common.eventbus.AsyncEventBus;
import com.google.common.eventbus.Subscribe;
import com.xa.mass.engine.monkey.event.TaskReviewEvent;
import java.util.concurrent.Executors;

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

        // 2. 生成任务
        List<Task> allTasks = generateTasks(root);
        log.info("Created tasks: {}", allTasks.size());

        // 3. 生成设备和 token
        DeviceManager deviceManager = new DeviceManager();
        List<Token> tokenList = generateDevicesAndTokens(root, deviceManager);
        log.info("Created devices: {}", tokenList.size());

        // 4. 初始化 AsyncEventBus 并注册审核监听器（异步事件总线）
        AsyncEventBus eventBus = new AsyncEventBus(Executors.newFixedThreadPool(4));
        eventBus.register(new TaskReviewListener(allTasks));

        // 5. 发布审核事件（异步处理）
        for (Task task : allTasks) {
            eventBus.post(new TaskReviewEvent.TaskReviewRandomEvent(task.getTid(), 1.0)); // 1.0 表示100%通过
        }
        log.info("(Async) Approved tasks: {}", allTasks.stream().filter(t -> t.getStatus() == TaskStatus.READY).count());

        // 6. 初始化监听器，模拟流式分配
        AssignmentRecordService recordService = new AssignmentRecordService();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);

        // 7. 启动异步任务分配 worker
        TaskAssignWorker assignWorker = new TaskAssignWorker(deviceAssignListener);
        
        // 注册任务完成监听器
        assignWorker.addListener(new TaskCompletionListener() {
            @Override
            public void onTaskCompleted(Task task) {
                log.debug("Task completed: {}", task.getTid());
            }
            
            @Override
            public void onAllTasksCompleted() {
                log.info("All tasks completed, running pipeline...");
                // 异步执行 pipeline
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
        
        assignWorker.start();
        CompletableFuture<Void> allTasksFuture = assignWorker.submitAll(allTasks);
        
        // 主流程等待所有任务完成（非阻塞）
        allTasksFuture.thenRun(() -> {
            log.info("All tasks and pipeline processing completed");
            assignWorker.stop();
        });
        
        // 主流程不阻塞，直接结束
        log.info("Mock task engine started, tasks are being processed asynchronously");
    }

    // 抽取任务生成逻辑
    private List<Task> generateTasks(JsonObject root) {
        List<Task> allTasks = new ArrayList<>();
        TaskManager taskManager = initTaskManger();
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