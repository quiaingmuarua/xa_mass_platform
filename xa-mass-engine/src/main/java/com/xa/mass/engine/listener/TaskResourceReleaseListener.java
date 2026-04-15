package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.task.TaskStatus;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Releases runtime-only resource occupancy when a task reaches TERMINAL.
 */
public class TaskResourceReleaseListener {

    private static final Logger log = LoggerFactory.getLogger(TaskResourceReleaseListener.class);

    private final TaskManager taskManager;
    private final DeviceManager deviceManager;
    private final Consumer<Task> dispatchRequester;

    public TaskResourceReleaseListener(TaskManager taskManager,
                                       DeviceManager deviceManager,
                                       Consumer<Task> dispatchRequester) {
        this.taskManager = taskManager;
        this.deviceManager = deviceManager;
        this.dispatchRequester = dispatchRequester;
    }

    public void onTaskTerminal(Task task) {
        if (task == null) {
            return;
        }

        List<TaskMsg> messages = taskManager.getTaskMessages(task.getTid());
        Set<String> deviceIds = new LinkedHashSet<>();

        for (TaskMsg message : messages) {
            if (message == null || message.getDeviceId() == null || message.getDeviceId().isBlank()) {
                continue;
            }
            deviceIds.add(message.getDeviceId());
            releaseTokenIfOwnedByTask(task.getTid(), message.getDeviceId(), message.getTokenId());
        }

        for (String deviceId : deviceIds) {
            deviceManager.unlockDevice(deviceId);
        }
    }

    public void onTaskMessageFinal(Task task, TaskMsg taskMsg) {
        if (task == null || taskMsg == null || task.getStatus().isFinal()) {
            return;
        }
        String deviceId = taskMsg.getDeviceId();
        if (deviceId == null || deviceId.isBlank()) {
            return;
        }
        boolean deviceStillBusy = taskManager.getTaskMessages(task.getTid()).stream()
                .filter(message -> message != null)
                .filter(message -> deviceId.equals(message.getDeviceId()))
                .anyMatch(TaskMsg::isProcessing);
        if (deviceStillBusy) {
            return;
        }

        releaseTokenIfOwnedByTask(task.getTid(), deviceId, taskMsg.getTokenId());
        deviceManager.unlockDevice(deviceId);

        if (dispatchRequester != null
                && task.getStatus() == TaskStatus.RUNNING
                && taskManager.hasPendingDispatchableMessages(task.getTid())) {
            dispatchRequester.accept(task);
        }
    }

    private void releaseTokenIfOwnedByTask(String taskId, String deviceId, String tokenId) {
        if (tokenId == null || tokenId.isBlank()) {
            return;
        }

        Token token = deviceManager.getToken(deviceId);
        if (token == null || !tokenId.equals(token.getTokenId())) {
            return;
        }
        if (token.getLastBindTaskId() != null && !taskId.equals(token.getLastBindTaskId())) {
            return;
        }
        if (token.getStatus() == TokenStatus.IDLE) {
            return;
        }
        if (token.release()) {
            deviceManager.updateToken(deviceId, token);
            return;
        }

        log.warn("Token {} on device {} could not be released from status {} for task {}",
                tokenId, deviceId, token.getStatus(), taskId);
    }
}
