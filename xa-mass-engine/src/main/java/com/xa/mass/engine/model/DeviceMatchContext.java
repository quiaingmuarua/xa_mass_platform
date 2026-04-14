package com.xa.mass.engine.model;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;

import java.util.HashMap;
import java.util.Map;

/**
 * Rule-evaluation context for device matching.
 *
 * <p>The routing-country signal is task-owned input, but the country truth used
 * for matching should come from token/account-facing data rather than device
 * grouping. Device group remains exposed only as a diagnostic signal.
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

        ctx.put("deviceId", device.getDeviceId());
        ctx.put("deviceStatus", device.getStatus().name());
        ctx.put("deviceGroupId", device.getDeviceGroupId());
        ctx.put("deviceAttributes", device.getAttributes());
        ctx.put("agentVersion", device.getAgentVersion());
        ctx.put("supportedProjects", device.getSupportedProjects());
        ctx.put("isDeviceAvailable", device.isAvailable());
        ctx.put("isDeviceLocked", deviceManager.isLocked(device.getDeviceId()));

        if (token != null) {
            ctx.put("tokenId", token.getTokenId());
            ctx.put("tokenStatus", token.getStatus().name());
            ctx.put("tokenChannel", token.getChannel());
            ctx.put("tokenAttributes", token.getAttributes());
            ctx.put("isTokenAllocatable", token.isAllocatable());
            ctx.put("isTokenAvailable", token.isAvailable());
        } else {
            ctx.put("tokenId", null);
            ctx.put("tokenStatus", null);
            ctx.put("tokenChannel", null);
            ctx.put("tokenAttributes", Map.of());
            ctx.put("isTokenAllocatable", false);
            ctx.put("isTokenAvailable", false);
        }

        String routingCountryCode = task.getTaskRoutingCountryCode();
        String tokenAttributeCountry = token != null ? token.getAttributes().get("country") : null;

        ctx.put("taskId", task.getTid());
        ctx.put("taskName", task.getTaskName());
        ctx.put("taskProject", task.getProject());
        ctx.put("taskRoutingCountryCode", routingCountryCode);
        ctx.put("taskStatus", task.getStatus().name());
        ctx.put("taskTargetNumber", task.getTaskTargetNumber());
        ctx.put("batchSize", task.getBatchSize());
        ctx.put("runTaskMinDeviceCnt", task.getRunTaskMinDeviceCnt());

        ctx.put("appCount", device.getSupportedProjects() != null ? device.getSupportedProjects().size() : 0);
        ctx.put("supportsProject", device.getSupportedProjects() != null &&
                device.getSupportedProjects().contains(task.getProject()));
        ctx.put("deviceGroupIdEqualsRoutingCountry",
                routingCountryCode != null && routingCountryCode.equals(device.getDeviceGroupId()));
        ctx.put("tokenChannelMatchesRoutingCountry",
                token != null && routingCountryCode != null && routingCountryCode.equals(token.getChannel()));
        ctx.put("tokenAttributeCountryMatchesRoutingCountry",
                routingCountryCode != null && routingCountryCode.equals(tokenAttributeCountry));

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
                ", tokenChannelMatchesRoutingCountry=" + context.get("tokenChannelMatchesRoutingCountry") +
                '}';
    }
}
