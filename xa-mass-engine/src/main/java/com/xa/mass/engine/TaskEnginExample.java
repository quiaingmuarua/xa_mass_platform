package com.xa.mass.engine;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.DeviceStatus;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.engine.model.enums.TokenStatus;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.TaskMsg;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.engine.rules.RuleManagerFactory;
import com.xa.mass.engine.assign.TaskDeviceAssignListener;
import com.xa.mass.engine.assign.SimpleTaskMsgAssignListener;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class TaskEnginExample {
    public static TaskManager initTaskManger() {
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        return new TaskManager(scheduler);
    }

    public static void main(String[] args) throws Exception {
        TaskManager taskManager = initTaskManger();
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

        // 2. 批量创建设备和token
        DeviceManager deviceManager = new DeviceManager();
        int deviceCount = 500;
        String[] deviceCountries = {"us", "gb", "fr"};
        for (int i = 0; i < deviceCount; i++) {
            Device device = new Device();
            device.setDeviceId("device-" + i);
            device.setStatus(DeviceStatus.ONLINE);
            device.setGroupId(deviceCountries[i % deviceCountries.length]);
            device.setAgentVersion("1.0.0");
            List<String> supportedApps = new ArrayList<>();
            supportedApps.add("demoApp");
            if (i % 3 == 0) supportedApps.add("otherApp");
            if (i % 5 == 0) supportedApps.add("testApp");
            device.setSupportedApps(supportedApps);
            deviceManager.addDevice(device);
            Token token = new Token();
            token.setTokenId("token-" + i);
            token.setDeviceId(device.getDeviceId());
            token.setChannel(device.getGroupId());
            token.setStatus(ThreadLocalRandom.current().nextBoolean() ? TokenStatus.LOGIN_READY : TokenStatus.INVALID);
            deviceManager.addToken(device.getDeviceId(), token);
//            System.out.println("new_device " + device);
        }
        System.out.println("Created devices: " + deviceCount);

        // 3. 审核任务（设为READY）
        for (Task task : allTasks) {
            task.transitionTo(TaskStatus.READY);
        }
        System.out.println("Approved tasks: " + allTasks.stream().filter(t -> t.getStatus() == TaskStatus.READY).count());

        // 4. 初始化监听器，模拟流式分配
        var ruleManager = RuleManagerFactory.getProjectRuleManager(projectName);
        var msgAssignListener = new SimpleTaskMsgAssignListener(deviceManager);
        var deviceAssignListener = new TaskDeviceAssignListener(ruleManager, deviceManager, msgAssignListener);

        // 5. 模拟任务分配事件（流式）
        for (Task task : allTasks) {
            if (task.getStatus() == TaskStatus.READY) {
                deviceAssignListener.onTaskAssign(task);
            }
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
