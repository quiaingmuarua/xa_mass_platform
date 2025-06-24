package com.xa.mass.engine;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.DeviceStatus;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.engine.model.enums.TokenStatus;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.TaskMsg;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.engine.strategy.SimpleTaskScheduler;

import java.util.*;
import java.util.concurrent.ThreadLocalRandom;

public class TaskEnginExample {

    public static void main(String[] args) {
        // 1. 初始化调度器和任务管理器
        SimpleTaskScheduler scheduler = new SimpleTaskScheduler();
        TaskManager taskManager = new TaskManager(scheduler);
        String[] countries = {"gb", "us"};
        int msgPerTask = 20;
        List<Task> allTasks = new ArrayList<>();
        for (String country : countries) {
            TaskCreateRequestDto dto = new TaskCreateRequestDto();
            dto.setTaskName("Task-" + country);
            dto.setProject("demoApp");
            dto.setCountryCode(country);
            dto.setUserId("user-" + country);
            dto.setTextContent("content for " + country);
            // 这里设置targetList模拟2k条msg
            List<String> targetList = new ArrayList<>();
            for (int i = 0; i < msgPerTask; i++) {
                targetList.add("number-" + country + "-" + i);
            }
            dto.setTargetList(targetList);
            Task task = taskManager.createTask(dto);
            task.setRunTaskMinDeviceCnt(30);
            System.out.println("new_task " + task);
            allTasks.add(task);
        }
        System.out.println("Created tasks: " + allTasks.size());

        // 2. 批量创建设备和token
        DeviceManager deviceManager = new DeviceManager();
        int deviceCount = 100; // 3k
        String[] deviceCountries = {"us", "gb", "fr"};
        for (int i = 0; i < deviceCount; i++) {
            Device device = new Device();
            device.setDeviceId("device-" + i);
            device.setStatus(DeviceStatus.ONLINE);
            device.setGroupId(deviceCountries[i % deviceCountries.length]);
            deviceManager.addDevice(device);
            // 只绑定一个token
            Token token = new Token();
            token.setTokenId("token-" + i);
            token.setDeviceId(device.getDeviceId());
            token.setChannel(device.getGroupId());
            token.setStatus(ThreadLocalRandom.current().nextBoolean() ? TokenStatus.LOGIN_READY : TokenStatus.INVALID);
            deviceManager.addToken(device.getDeviceId(), token);
            System.out.println("new_device " + device);

        }
        System.out.println("Created devices: " + deviceCount);

        // 3. 审核任务（设为READY）
        for (Task task : allTasks) {
            task.transitionTo(TaskStatus.READY);
        }
        System.out.println("Approved tasks: " + allTasks.stream().filter(t -> t.getStatus() == TaskStatus.READY).count());

        // 4. 任务绑定设备
        Map<String, List<Device>> taskDeviceMap = new HashMap<>();
        for (Task task : allTasks) {
            if (task.getStatus() != TaskStatus.READY) continue;
            List<Device> candidates = deviceManager.getDevicesByCountry(task.getTaskCountry());
            List<Device> matched = new ArrayList<>();
            for (Device device : candidates) {
                if (matched.size() >= task.getRunTaskMinDeviceCnt()) break;
                if (deviceManager.tryLockDevice(device.getDeviceId())) {
                    System.out.println("new matched " + task + device);
                    matched.add(device);
                }
            }
            if (matched.size() >= task.getRunTaskMinDeviceCnt()) {
                task.setScheduleDeviceCnt(matched.size());
                task.transitionTo(TaskStatus.RUNNING);

                taskDeviceMap.put(task.getTid(), matched);
            } else {
                // 匹配失败，释放锁
                for (Device d : matched) deviceManager.unlockDevice(d.getDeviceId());
            }
        }
        System.out.println("Tasks scheduled: " + taskDeviceMap.size());

        // 5. 消息批次与推送队列
        List<TaskMsg> pushQueue = new ArrayList<>();
        for (Map.Entry<String, List<Device>> entry : taskDeviceMap.entrySet()) {
            String taskId = entry.getKey();
            List<Device> devices = entry.getValue();
            int batchSize = 8;
            int batchId = 0;
            for (Device device : devices) {
                for (int i = 0; i < batchSize; i++) {
                    String msgId = UUID.randomUUID().toString();
                    // 绑定token到消息
                    Token token = deviceManager.getToken(device.getDeviceId());
                    String tokenId = token != null ? token.getTokenId() : null;
                    TaskMsg msg = new TaskMsg(msgId, taskId, device.getDeviceId(), tokenId, "batch-" + batchId);
                    System.out.println(msg);
                    pushQueue.add(msg);
                }
            }
        }
        System.out.println("Push queue size: " + pushQueue.size());
        // 可在此处模拟推送到 gateway inputQueue
    }
}
