package com.xa.mass.mock.starter;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.report.ConflictReportStep;
import com.xa.mass.engine.report.RuleEvaluationStep;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.assign.TaskDeviceAssignListener;
import com.xa.mass.engine.assign.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.mock.engine.MockDeviceGenerator;
import com.xa.mass.mock.engine.MockTaskGenerator;
import com.xa.mass.mock.starter.TaskAssignWorker;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.CommandLineRunner;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import java.util.*;
import java.nio.file.Files;
import java.nio.file.Path;
import java.io.IOException;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import java.util.List;
import com.xa.mass.engine.report.AssignmentPipelineStep;
import com.xa.mass.engine.report.AssignmentReportStep;

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

        // 4. 审核任务（设为READY）
        for (Task task : allTasks) {
            task.transitionTo(TaskStatus.READY);
        }
        log.info("Approved tasks: {}", allTasks.stream().filter(t -> t.getStatus() == TaskStatus.READY).count());

        // 5. 初始化监听器，模拟流式分配
        AssignmentRecordService recordService = new AssignmentRecordService();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);

        // 6. 启动异步任务分配 worker
        TaskAssignWorker assignWorker = new TaskAssignWorker(deviceAssignListener);
        assignWorker.start();
        for (Task task : allTasks) {
            assignWorker.submit(task);
        }
        // 等待所有任务分配完成（简单 sleep，可优化为更优雅的同步机制）
        Thread.sleep(1000L);
        assignWorker.stop();

        // 7-9. pipeline 观测/归因处理
        List<AssignmentPipelineStep> pipeline = List.of(
            new AssignmentReportStep(true),
            new ConflictReportStep(true),
            new RuleEvaluationStep(true)
        );
        for (AssignmentPipelineStep step : pipeline) {
            if (step.isEnabled()) step.process(recordService);
        }
    }

    // 抽取任务生成逻辑
    private List<Task> generateTasks(JsonObject root) {
        List<Task> allTasks = new ArrayList<>();
        TaskManager taskManager = initTaskManger();
        if (root.has("tasks")) {
            List<TaskCreateRequestDto> taskDtos = MockTaskGenerator.generateTasks(root.getAsJsonArray("tasks"));
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
            List<Device> devices = MockDeviceGenerator.generateDevices(root.getAsJsonArray("devices").toString(), tokenList);
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
                "  \"devices\": " + MockDeviceGenerator.exampleJsonDsl() + ",\n" +
                "  \"tasks\": " + MockTaskGenerator.exampleTasksJsonDsl() + "\n" +
                "}";
    }

    // 抽取分配统计报告
    private void printAssignmentReport(AssignmentRecordService recordService) {
        log.info("\n=== 分配统计报告 ===");
        Map<String, Object> report = recordService.generateAssignmentReport();
        log.info("总分配记录数: {}", report.get("totalRecords"));
        log.info("成功分配数: {}", report.get("successCount"));
        log.info("失败分配数: {}", report.get("failedCount"));
        log.info("规则不匹配数: {}", report.get("ruleNotMatchCount"));
        log.info("冲突数: {}", report.get("conflictCount"));
        log.info("成功率: {}%", String.format("%.2f", (Double) report.get("successRate") * 100));
    }

    // 抽取冲突检测
    private void printConflictReport(AssignmentRecordService recordService) {
        log.info("\n=== 冲突检测 ===");
        List<Map<String, Object>> conflicts = recordService.detectConflicts();
        if (conflicts.isEmpty()) {
            log.info("未检测到冲突");
        } else {
            log.info("检测到 {} 个潜在冲突:", conflicts.size());
            for (Map<String, Object> conflict : conflicts) {
                log.info("  设备: {}, 冲突类型: {}, 时间间隔: {} 分钟", conflict.get("deviceId"), conflict.get("conflictType"), conflict.get("timeDiffMinutes"));
            }
        }
    }

    // 抽取规则评估详情
    private void printRuleEvaluationDetails(AssignmentRecordService recordService) {
        log.info("\n=== 规则评估详情 ===");
        List<AssignmentRecord> ruleNotMatchRecords = recordService.getRuleNotMatchRecords();
        if (!ruleNotMatchRecords.isEmpty()) {
            log.info("规则不匹配详情 (前5条):");
            ruleNotMatchRecords.stream().limit(5).forEach(record -> {
                log.info("  设备: {}, 任务: {}, 原因: {}", record.getDeviceId(), record.getTaskId(), record.getReason());
            });
        }
    }
} 