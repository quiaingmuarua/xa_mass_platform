package com.xa.mass.engine;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.enums.DeviceStatus;
import com.xa.mass.engine.model.enums.TaskStatus;
import com.xa.mass.engine.model.enums.TokenStatus;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.model.TaskMsg;
import com.xa.mass.engine.model.task.TaskCreateRequestDto;
import com.xa.mass.engine.model.common.User;

import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;
import java.util.stream.Collectors;

public class TaskEnginExample {

    public static void main(String[] args) {
        // 1. 批量创建任务
        TaskManager taskManager = new TaskManager(null);
        int taskCount = 2000;
        String[] countries = {"gb", "us"};
        List<Task> allTasks = new ArrayList<>();
        for (int i = 0; i < taskCount; i++) {
            TaskCreateRequestDto dto = new TaskCreateRequestDto();
            dto.setTaskName("Task-" + i);
            dto.setProject("demoApp");
            dto.setCountryCode(countries[i % countries.length]);
            dto.setUserId("user" + (i % 10));
            dto.setTextContent("content" + i);
            Task task = taskManager.createTask(dto);
            // 设置最低匹配设备数
            task.setRunTaskMinDeviceCnt(30);
            allTasks.add(task);
        }
        System.out.println("Created tasks: " + allTasks.size());

        // 2. 批量创建设备和token
        DeviceManager deviceManager = new DeviceManager();
        int deviceCount = 3000000; // 3kk
        String[] deviceCountries = {"us", "gb", "fr"};
        for (int i = 0; i < deviceCount; i++) {
            Device device = new Device();
            device.setDeviceId("device-" + i);
            device.setStatus(DeviceStatus.ONLINE);
            device.setGroupId(deviceCountries[i % deviceCountries.length]);
            deviceManager.addDevice(device);
            // 随机生成1~3个token
            int tokenNum = 1 + ThreadLocalRandom.current().nextInt(3);
            for (int t = 0; t < tokenNum; t++) {
                Token token = new Token();
                token.setTokenId("token-" + i + "-" + t);
                token.setDeviceId(device.getDeviceId());
                token.setChannel(device.getGroupId());
                token.setStatus(ThreadLocalRandom.current().nextBoolean() ? TokenStatus.LOGIN_READY : TokenStatus.INVALID);
                deviceManager.addToken(device.getDeviceId(), token);
            }
        }
        System.out.println("Created devices: " + deviceCount);

        // 3. 审核部分任务（设为READY）
        for (Task task : allTasks) {
            if (ThreadLocalRandom.current().nextDouble() < 0.8) { // 80%通过
                task.transitionTo(TaskStatus.READY);
            }
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
                    TaskMsg msg = new TaskMsg(msgId, taskId, device.getDeviceId(), null, "batch-" + batchId);
                    pushQueue.add(msg);
                }
                batchId++;
            }
        }
        System.out.println("Push queue size: " + pushQueue.size());
        // 可在此处模拟推送到 gateway inputQueue
    }

    // 模拟 DeviceManager
    static class DeviceManager {
        private final Map<String, Device> devices = new ConcurrentHashMap<>();
        private final Map<String, List<Token>> deviceTokens = new ConcurrentHashMap<>();
        private final Set<String> lockedDevices = Collections.synchronizedSet(new HashSet<>());

        public void addDevice(Device device) {
            devices.put(device.getDeviceId(), device);
        }
        public void addToken(String deviceId, Token token) {
            deviceTokens.computeIfAbsent(deviceId, k -> new ArrayList<>()).add(token);
        }
        public List<Device> getDevicesByCountry(String country) {
            return devices.values().stream()
                    .filter(d -> d.getGroupId().equals(country))
                    .collect(Collectors.toList());
        }
        public List<Token> getTokens(String deviceId) {
            return deviceTokens.getOrDefault(deviceId, Collections.emptyList());
        }
        public boolean tryLockDevice(String deviceId) {
            return lockedDevices.add(deviceId);
        }
        public void unlockDevice(String deviceId) {
            lockedDevices.remove(deviceId);
        }
        public boolean isLocked(String deviceId) {
            return lockedDevices.contains(deviceId);
        }
    }
}
