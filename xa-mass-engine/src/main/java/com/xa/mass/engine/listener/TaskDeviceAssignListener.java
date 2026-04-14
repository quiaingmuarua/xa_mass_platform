package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
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
    private final TaskMsgAssignListener msgAssignListener;
    private final TaskManager taskManager;

    public TaskDeviceAssignListener(RuleManager<Map<String, Object>> ruleManager,
                                    DeviceManager deviceManager,
                                    TaskMsgAssignListener msgAssignListener,
                                    AssignmentRecordService recordService,
                                    TaskManager taskManager) {
        this(new RuleBasedTaskDeviceMatchingStrategy(ruleManager, deviceManager, recordService), msgAssignListener, taskManager);
    }

    public TaskDeviceAssignListener(TaskDeviceMatchingStrategy matchingStrategy,
                                    TaskMsgAssignListener msgAssignListener,
                                    TaskManager taskManager) {
        this.matchingStrategy = matchingStrategy;
        this.msgAssignListener = msgAssignListener;
        this.taskManager = taskManager;
    }

    /**
     * Processes a task assignment attempt.
     */
    public void onTaskAssign(Task task) {
        int maxDeviceCount = (int) Math.ceil((double) task.getTaskTargetNumber() / task.getBatchSize());
        int batchSize = Math.max(task.getRunTaskMinDeviceCnt(), maxDeviceCount);
        List<Device> matched = matchDevicesWithRules(task, batchSize);
        if (matched.isEmpty()) {
            return;
        }
        if (task.getStatus() != TaskStatus.READY) {
            log.info("[DeviceAssign] Skip dispatch for task {} because status changed to {} during matching",
                    task.getTid(), task.getStatus());
            return;
        }

        task.setScheduleDeviceCnt(matched.size());
        if (!task.transitionTo(TaskStatus.RUNNING)) {
            log.warn("[DeviceAssign] Failed to transition task {} from READY to RUNNING", task.getTid());
            return;
        }

        taskManager.updateTask(task);
        msgAssignListener.onMsgAssign(task, matched);
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
}
