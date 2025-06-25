package com.xa.mass.mock.engine;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.DeviceStatus;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.engine.model.enums.TokenStatus;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.engine.model.AssignmentRecord;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.assign.TaskDeviceAssignListener;
import com.xa.mass.engine.assign.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;
import com.xa.mass.mock.engine.MockDeviceGenerator;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Mock 全链路任务分配主流程入口，支持 JSON-DSL mock 配置。
 */
public class MockTaskEngineExample {
    public static TaskManager initTaskManger() {
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        return new TaskManager(scheduler);
    }

    public static void main(String[] args) throws Exception {
        TaskManager taskManager = initTaskManger();
        AssignmentRecordService recordService = new AssignmentRecordService();
        var ruleManager = RuleManagerFactory.getProjectRuleManager("demoApp");

        // 1. 任务 mock
        String[] countries = {"gb", "us"};
        int msgPerTask = 50;
        int batchSize = 5;
        String projectName = "demoApp";
        List<Task> allTasks = new ArrayList<>();
        for (String country : countries) {
            TaskCreateRequestDto dto = getTaskCreateRequestDto(country, projectName, msgPerTask, batchSize);
            Task task = taskManager.createTask(dto);
            task.setRunTaskMinDeviceCnt(5);
            task.setBatchSize(batchSize);
            System.out.println("new_task " + task);
            allTasks.add(task);
        }
        System.out.println("Created tasks: " + allTasks.size());

        // 2. 设备和 token mock（JSON-DSL 驱动）
        DeviceManager deviceManager = new DeviceManager();
        List<Token> tokenList = new ArrayList<>();
        String jsonDsl = MockDeviceGenerator.exampleJsonDsl(); // 可替换为自定义 JSON-DSL
        List<Device> devices = MockDeviceGenerator.generateDevices(jsonDsl, tokenList);
        for (Device device : devices) {
            deviceManager.addDevice(device);
        }
        for (Token token : tokenList) {
            deviceManager.addToken(token.getDeviceId(), token);
        }
        System.out.println("Created devices: " + devices.size());

        // 3. 审核任务（设为READY）
        for (Task task : allTasks) {
            task.transitionTo(TaskStatus.READY);
        }
        System.out.println("Approved tasks: " + allTasks.stream().filter(t -> t.getStatus() == TaskStatus.READY).count());

        // 4. 初始化监听器，模拟流式分配
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager, recordService);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener, recordService);

        // 5. 模拟任务分配事件（流式）
        for (Task task : allTasks) {
            if (task.getStatus() == TaskStatus.READY) {
                deviceAssignListener.onTaskAssign(task);
            }
        }

        // 6. 生成分配统计报告
        System.out.println("\n=== 分配统计报告 ===");
        Map<String, Object> report = recordService.generateAssignmentReport();
        System.out.println("总分配记录数: " + report.get("totalRecords"));
        System.out.println("成功分配数: " + report.get("successCount"));
        System.out.println("失败分配数: " + report.get("failedCount"));
        System.out.println("规则不匹配数: " + report.get("ruleNotMatchCount"));
        System.out.println("冲突数: " + report.get("conflictCount"));
        System.out.println("成功率: " + String.format("%.2f%%", (Double) report.get("successRate") * 100));

        // 7. 冲突检测
        System.out.println("\n=== 冲突检测 ===");
        List<Map<String, Object>> conflicts = recordService.detectConflicts();
        if (conflicts.isEmpty()) {
            System.out.println("未检测到冲突");
        } else {
            System.out.println("检测到 " + conflicts.size() + " 个潜在冲突:");
            for (Map<String, Object> conflict : conflicts) {
                System.out.println("  设备: " + conflict.get("deviceId") +
                        ", 冲突类型: " + conflict.get("conflictType") +
                        ", 时间间隔: " + conflict.get("timeDiffMinutes") + " 分钟");
            }
        }

        // 8. 规则评估详情
        System.out.println("\n=== 规则评估详情 ===");
        List<AssignmentRecord> ruleNotMatchRecords = recordService.getRuleNotMatchRecords();
        if (!ruleNotMatchRecords.isEmpty()) {
            System.out.println("规则不匹配详情 (前5条):");
            ruleNotMatchRecords.stream().limit(5).forEach(record -> {
                System.out.println("  设备: " + record.getDeviceId() +
                        ", 任务: " + record.getTaskId() +
                        ", 原因: " + record.getReason());
            });
        }
    }

    private static TaskCreateRequestDto getTaskCreateRequestDto(String country, String projectName, int msgPerTask, int batchSize) {
        TaskCreateRequestDto dto = new TaskCreateRequestDto();
        dto.setTaskName("Task-" + country);
        dto.setProject(projectName);
        dto.setCountryCode(country);
        dto.setUserId("user-" + country);
        dto.setTextContent("content for " + country);
        List<String> targetList = new ArrayList<>();
        for (int i = 0; i < msgPerTask; i++) {
            targetList.add("number-" + country + "-" + i);
        }
        dto.setTargetList(targetList);
        dto.setBatchSize(batchSize);
        return dto;
    }
} 