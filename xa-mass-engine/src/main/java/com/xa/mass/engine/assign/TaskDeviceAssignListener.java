package com.xa.mass.engine.assign;

import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.model.DeviceMatchContext;
import com.xa.mass.engine.model.Token;

import java.util.*;

/**
 * 任务分配监听器：监听任务分配事件，按批次分配设备
 */
public class TaskDeviceAssignListener {
    private final RuleManager<Map<String, Object>> ruleManager;
    private final DeviceManager deviceManager;
    private final TaskMsgAssignListener msgAssignListener;

    public TaskDeviceAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    DeviceManager deviceManager,
                                    TaskMsgAssignListener msgAssignListener) {
        this.ruleManager = ruleManager;
        this.deviceManager = deviceManager;
        this.msgAssignListener = msgAssignListener;
    }

    /**
     * 监听到任务分配事件，进行一批设备分配
     */
    public void onTaskAssign(Task task) {
        int maxDeviceCount = (int) Math.ceil((double) task.getTaskInitNumber() / task.getBatchSize());
        int batchSize = Math.min(task.getRunTaskMinDeviceCnt(), maxDeviceCount);
        List<Device> matched = matchDevicesWithRules(task, batchSize);
        if (!matched.isEmpty()) {
            // 推送到消息分配监听器
            msgAssignListener.onMsgAssign(task, matched);
        }
    }

    /**
     * 使用规则引擎匹配设备
     */
    private List<Device> matchDevicesWithRules(Task task, int maxDeviceCount) {
        List<Device> matchedDevices = new ArrayList<>();
        List<Device> candidates = deviceManager.getDevicesByCountry(task.getTaskCountry());
        for (Device device : candidates) {
            if (matchedDevices.size() >= maxDeviceCount) break;
            Token token = deviceManager.getToken(device.getDeviceId());
            DeviceMatchContext matchContext = new DeviceMatchContext(device, token, task);
            try {
                List<String> hitRules = ruleManager.evaluateDefaultRules(matchContext.getContext());
                if (hitRules.size() == ruleManager.getDefaultRules().size()) {
                    if (deviceManager.tryLockDevice(device.getDeviceId())) {
                        matchedDevices.add(device);
                    }
                }
            } catch (Exception e) {
                // 可记录日志
            }
        }
        return matchedDevices;
    }
} 