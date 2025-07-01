package com.xa.mass.engine.model;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;

import java.util.HashMap;
import java.util.Map;

/**
 * 设备匹配上下文
 * 封装设备、Token、任务等信息，用于规则评估
 */
public class DeviceMatchContext {
    private final Device device;
    private final Token token;
    private final Task task;
    private final DeviceManager deviceManager;
    private final Map<String, Object> context;

    public DeviceMatchContext(Device device, Token token, Task task, DeviceManager deviceManager) {
        this.device = device;
        this.token = token;
        this.task = task;
        this.deviceManager = deviceManager;
        this.context = buildContext();
    }

    private Map<String, Object> buildContext() {
        Map<String, Object> ctx = new HashMap<>();

        // 设备相关属性
        ctx.put("deviceId", device.getDeviceId());
        ctx.put("deviceStatus", device.getStatus().name());
        ctx.put("deviceGroupId", device.getGroupId());
        ctx.put("agentVersion", device.getAgentVersion());
        ctx.put("supportedProjects", device.getSupportedProjects());
        ctx.put("isDeviceAvailable", device.isAvailable());
        // 使用DeviceManager检查锁定状态，确保与分配逻辑一致
        ctx.put("isDeviceLocked", deviceManager.isLocked(device.getDeviceId()));

        // Token相关属性
        if (token != null) {
            ctx.put("tokenId", token.getTokenId());
            ctx.put("tokenStatus", token.getStatus().name());
            ctx.put("tokenChannel", token.getChannel());
            ctx.put("isTokenAllocatable", token.isAllocatable());
            ctx.put("isTokenAvailable", token.isAvailable());
        } else {
            ctx.put("tokenId", null);
            ctx.put("tokenStatus", null);
            ctx.put("tokenChannel", null);
            ctx.put("isTokenAllocatable", false);
            ctx.put("isTokenAvailable", false);
        }

        // 任务相关属性
        ctx.put("taskId", task.getTid());
        ctx.put("taskName", task.getTaskName());
        ctx.put("taskProject", task.getProject());
        ctx.put("taskCountry", task.getTaskCountry());
        ctx.put("taskStatus", task.getStatus().name());
        ctx.put("taskInitNumber", task.getTaskInitNumber());
        ctx.put("batchSize", task.getBatchSize());
        ctx.put("runTaskMinDeviceCnt", task.getRunTaskMinDeviceCnt());

        // 计算属性
        ctx.put("appCount", device.getSupportedProjects() != null ? device.getSupportedProjects().size() : 0);
        ctx.put("supportsProject", device.getSupportedProjects() != null &&
                device.getSupportedProjects().contains(task.getProject()));
        ctx.put("countryMatch", task.getTaskCountry().equals(device.getGroupId()));
        ctx.put("channelMatch", token != null && task.getTaskCountry().equals(token.getChannel()));

        return ctx;
    }

    public Device getDevice() {
        return device;
    }

    public Token getToken() {
        return token;
    }

    public Task getTask() {
        return task;
    }

    public DeviceManager getDeviceManager() {
        return deviceManager;
    }

    public Map<String, Object> getContext() {
        return context;
    }

    @Override
    public String toString() {
        return "DeviceMatchContext{" +
                "deviceId='" + device.getDeviceId() + '\'' +
                ", taskId='" + task.getTid() + '\'' +
                ", supportsProject=" + context.get("supportsProject") +
                ", countryMatch=" + context.get("countryMatch") +
                '}';
    }
} 