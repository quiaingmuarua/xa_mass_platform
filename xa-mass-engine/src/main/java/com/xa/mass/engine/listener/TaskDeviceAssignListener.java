package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.rules.RuleManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import com.xa.mass.engine.strategy.RuleBasedTaskDeviceMatchingStrategy;
import com.xa.mass.engine.strategy.TaskDeviceMatchingStrategy;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * Listens for task assignment events and delegates device matching to a pluggable strategy.
 */
public class TaskDeviceAssignListener {
    private static final Logger log = LoggerFactory.getLogger(TaskDeviceAssignListener.class);

    private final TaskDeviceMatchingStrategy matchingStrategy;
    private final DeviceManager deviceManager;
    private final TaskMsgAssignListener msgAssignListener;
    private final TaskManager taskManager;

    public TaskDeviceAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    DeviceManager deviceManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    AssignmentRecordService recordService,
                                    TaskManager taskManager) {
        this(new RuleBasedTaskDeviceMatchingStrategy(ruleManager, deviceManager, recordService),
                deviceManager, msgAssignListener, taskManager);
    }

    public TaskDeviceAssignListener(TaskDeviceMatchingStrategy matchingStrategy,
                                    DeviceManager deviceManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    TaskManager taskManager) {
        this.matchingStrategy = matchingStrategy;
        this.deviceManager = deviceManager;
        this.msgAssignListener = msgAssignListener;
        this.taskManager = taskManager;
    }

    /**
     * Processes a task assignment attempt.
     */
    public boolean onTaskAssign(Task task) {
        TaskStatus initialStatus = task.getStatus();
        if (initialStatus != TaskStatus.READY && initialStatus != TaskStatus.RUNNING) {
            return false;
        }

        int pendingDispatchCount = taskManager.countPendingDispatchableMessages(task.getTid());
        if (pendingDispatchCount <= 0) {
            return false;
        }

        int desiredDispatchDeviceCount = getDesiredDispatchDeviceCount(task, pendingDispatchCount);
        int requiredStartDeviceCount = initialStatus == TaskStatus.READY
                ? getRequiredStartDeviceCount(task)
                : 1;
        int matchRequestCount = Math.max(requiredStartDeviceCount, desiredDispatchDeviceCount);
        List<Device> matched = matchDevicesWithRules(task, matchRequestCount);
        if (matched.isEmpty()) {
            return false;
        }
        if (initialStatus == TaskStatus.READY && matched.size() < requiredStartDeviceCount) {
            log.info("[DeviceAssign] Keep task {} in READY because matched devices {} are below required minimum {}",
                    task.getTid(), matched.size(), requiredStartDeviceCount);
            unlockDevices(matched);
            return false;
        }
        if (task.getStatus() != initialStatus) {
            log.info("[DeviceAssign] Skip dispatch for task {} because status changed from {} to {} during matching",
                    task.getTid(), initialStatus, task.getStatus());
            unlockDevices(matched);
            return false;
        }

        List<Device> dispatchDevices = matched.subList(0, Math.min(matched.size(), desiredDispatchDeviceCount));
        if (dispatchDevices.isEmpty()) {
            unlockDevices(matched);
            return false;
        }
        unlockDevices(matched.subList(dispatchDevices.size(), matched.size()));

        List<TaskMsg> dispatchedMessages = msgAssignListener.onMsgAssign(task, List.copyOf(dispatchDevices));
        long usedDeviceCount = dispatchedMessages.stream()
                .map(TaskMsg::getDeviceId)
                .filter(deviceId -> deviceId != null && !deviceId.isBlank())
                .distinct()
                .count();
        if (usedDeviceCount <= 0) {
            return false;
        }

        task.setScheduleDeviceCnt(Math.max(task.getScheduleDeviceCnt(), (int) usedDeviceCount));
        if (initialStatus == TaskStatus.READY && !task.transitionTo(TaskStatus.RUNNING)) {
            log.warn("[DeviceAssign] Failed to transition task {} from READY to RUNNING", task.getTid());
            unlockDevices(dispatchDevices);
            return false;
        }
        taskManager.updateTask(task);
        return true;
    }

    /**
     * Kept for compatibility with current tests and callers; the implementation is now strategy-based.
     */
    List<Device> matchDevicesWithRules(Task task, int maxDeviceCount) {
        List<Device> matchedDevices = matchingStrategy.matchDevices(task, maxDeviceCount);
        log.info("[DeviceAssign] Strategy {} matched {} devices for task {}",
                matchingStrategy.getClass().getSimpleName(), matchedDevices.size(), task.getTid());
        return matchedDevices;
    }

    private int getDesiredDispatchDeviceCount(Task task, int pendingDispatchCount) {
        int remainingMessages = Math.max(pendingDispatchCount, 1);
        return Math.max(1, (int) Math.ceil((double) remainingMessages / task.getBatchSize()));
    }

    private int getRequiredStartDeviceCount(Task task) {
        return Math.max(task.getRunTaskMinDeviceCnt(), 1);
    }

    private void unlockDevices(List<Device> devices) {
        for (Device device : devices) {
            deviceManager.unlockDevice(device.getDeviceId());
        }
    }
}
