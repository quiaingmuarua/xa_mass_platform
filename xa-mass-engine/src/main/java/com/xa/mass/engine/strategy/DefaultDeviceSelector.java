package com.xa.mass.engine.strategy;

import com.xa.mass.core.engine.model.Device;
import com.xa.mass.core.engine.model.task.Task;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * 默认设备选择器实现
 */
public class DefaultDeviceSelector implements DeviceSelector {
    
    @Override
    public List<Device> selectDevices(Task task, List<Device> availableDevices, int requiredCount) {
        return availableDevices.stream()
                .filter(device -> isDeviceSuitable(device, task))
                .sorted(Comparator.comparingDouble(device -> -getDevicePriority(device, task)))
                .limit(requiredCount)
                .collect(Collectors.toList());
    }
    
    @Override
    public boolean isDeviceSuitable(Device device, Task task) {
        // 检查设备是否可用
        if (!device.isAvailable()) {
            return false;
        }
        
        // 检查设备是否被锁定
        if (device.isLocked()) {
            return false;
        }
        
        // 检查设备是否支持任务的应用
        if (!device.supportsApp(task.getProject())) {
            return false;
        }
        
        // 检查设备心跳是否正常（30秒内）
        if (device.isHeartbeatExpired(30)) {
            return false;
        }
        
        return true;
    }
    
    @Override
    public double getDevicePriority(Device device, Task task) {
        double priority = 0.0;
        
        // 基础分数：设备在线状态
        if (device.getStatus().isAvailable()) {
            priority += 100.0;
        }
        
        // 心跳时间越近，优先级越高
        if (device.getLastHeartbeat() != null) {
            long secondsSinceHeartbeat = java.time.Duration.between(
                    device.getLastHeartbeat(), 
                    java.time.LocalDateTime.now()
            ).getSeconds();
            
            // 心跳时间在30秒内，分数递减
            if (secondsSinceHeartbeat <= 30) {
                priority += (30 - secondsSinceHeartbeat) * 2.0;
            }
        }
        
        // 版本匹配加分
        if (device.getAgentVersion() != null && device.getAgentVersion().startsWith("1.")) {
            priority += 10.0;
        }
        
        // 分组匹配加分
        if (device.getGroupId() != null && task.getTaskCountry() != null) {
            if (device.getGroupId().contains(task.getTaskCountry())) {
                priority += 20.0;
            }
        }
        
        return priority;
    }
} 