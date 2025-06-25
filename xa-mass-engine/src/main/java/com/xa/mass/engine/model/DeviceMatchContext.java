package com.xa.mass.engine.model;

import com.xa.mass.eventbus.model.Device;
import com.xa.mass.eventbus.model.Task;
import com.xa.mass.eventbus.model.Token;

import java.util.HashMap;
import java.util.Map;

/**
 * 设备匹配上下文
 * 用于规则引擎评估设备是否匹配任务
 */
public class DeviceMatchContext {
    private final Device device;
    private final Token token;
    private final Task task;
    private final Map<String, Object> context;

    public DeviceMatchContext(Device device, Token token, Task task) {
        this.device = device;
        this.token = token;
        this.task = task;
        this.context = buildContext();
    }

    private Map<String, Object> buildContext() {
        Map<String, Object> ctx = new HashMap<>();

        // 设备相关属性
        ctx.put("deviceId", device.getDeviceId());
        ctx.put("deviceStatus", device.getStatus().name());
        ctx.put("deviceGroupId", device.getGroupId());
        ctx.put("agentVersion", device.getAgentVersion());
        ctx.put("supportedApps", device.getSupportedApps());
        ctx.put("isDeviceAvailable", device.isAvailable());
        ctx.put("isDeviceLocked", device.isLocked());

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
        ctx.put("appCount", device.getSupportedApps() != null ? device.getSupportedApps().size() : 0);
        ctx.put("supportsProject", device.getSupportedApps() != null &&
                device.getSupportedApps().contains(task.getProject()));
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

    public Map<String, Object> getContext() {
        return context;
    }

    @Override
    public String toString() {
        return "DeviceMatchContext{" +
                "deviceId='" + device.getDeviceId() + '\'' +
                ", tokenId='" + (token != null ? token.getTokenId() : "null") + '\'' +
                ", taskId='" + task.getTid() + '\'' +
                '}';
    }
} 