package com.xa.mass.engine.strategy;

import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;

import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Default device selector.
 *
 * <p>Selection priority stays device-centric. It must not inject routing-country
 * assumptions from {@code device.deviceGroupId}; routing country belongs to rule/token
 * matching instead.
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
        if (!device.isAvailable()) {
            return false;
        }
        if (device.isLocked()) {
            return false;
        }
        if (!device.supportsProject(task.getProject())) {
            return false;
        }
        return !device.isHeartbeatExpired(30);
    }

    @Override
    public double getDevicePriority(Device device, Task task) {
        double priority = 0.0;

        if (device.getStatus().isAvailable()) {
            priority += 100.0;
        }

        if (device.getLastHeartbeat() != null) {
            long secondsSinceHeartbeat = java.time.Duration.between(
                    device.getLastHeartbeat(),
                    java.time.LocalDateTime.now()
            ).getSeconds();

            if (secondsSinceHeartbeat <= 30) {
                priority += (30 - secondsSinceHeartbeat) * 2.0;
            }
        }

        if (device.getAgentVersion() != null && device.getAgentVersion().startsWith("1.")) {
            priority += 10.0;
        }

        return priority;
    }
}
