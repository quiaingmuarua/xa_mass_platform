package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.enums.task.TokenStatus;
import com.xa.mass.base.enums.taskmsg.TaskMsgStatus;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.engine.service.AssignmentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Binds persisted task messages to matched devices and emits the dispatch queue.
 */
public class SimpleTaskMsgAssignListener implements TaskMsgAssignListener {
    private static final Logger log = LoggerFactory.getLogger(SimpleTaskMsgAssignListener.class);

    private final TaskManager taskManager;
    private final DeviceManager deviceManager;
    private final AssignmentRecordService recordService;
    private final TaskMsgDispatchListener dispatchListener;

    public SimpleTaskMsgAssignListener(TaskManager taskManager,
                                       DeviceManager deviceManager,
                                       AssignmentRecordService recordService) {
        this(taskManager, deviceManager, recordService, null);
    }

    public SimpleTaskMsgAssignListener(TaskManager taskManager,
                                       DeviceManager deviceManager,
                                       AssignmentRecordService recordService,
                                       TaskMsgDispatchListener dispatchListener) {
        this.taskManager = taskManager;
        this.deviceManager = deviceManager;
        this.recordService = recordService;
        this.dispatchListener = dispatchListener;
    }

    @Override
    public List<TaskMsg> onMsgAssign(Task task, List<Device> devices) {
        if (devices == null || devices.isEmpty()) {
            log.info("[MsgAssign] Skip task {} because no matched devices were provided", task.getTid());
            return List.of();
        }

        List<TaskMsg> pendingMessages = taskManager.getTaskMessages(task.getTid()).stream()
                .filter(this::isPendingDispatch)
                .collect(Collectors.toList());
        int totalMessages = pendingMessages.size();
        if (totalMessages == 0) {
            log.info("[MsgAssign] Skip task {} because there are no pending task messages to dispatch", task.getTid());
            return List.of();
        }

        int perDeviceBatchLimit = Math.max(task.getBatchSize(), 1);
        int batchId = 0;
        int cursor = 0;
        List<TaskMsg> pushQueue = new ArrayList<>();
        List<DispatchSlot> dispatchSlots = new ArrayList<>();

        log.info("[MsgAssign] Starting assignment for task {} with {} devices, totalMessages={}, perDeviceBatchLimit={}",
                task.getTid(), devices.size(), totalMessages, perDeviceBatchLimit);

        for (int i = 0; i < devices.size() && cursor < totalMessages; i++) {
            Device device = devices.get(i);
            String currentBatchId = "batch-" + batchId;
            Token token = deviceManager.getToken(device.getDeviceId());
            if (!prepareTokenForDispatch(task, device, token)) {
                log.warn("[MsgAssign] Skip device {} for task {} because token state is not dispatchable",
                        device.getDeviceId(), task.getTid());
                deviceManager.unlockDevice(device.getDeviceId());
                batchId++;
                continue;
            }
            String tokenId = token != null ? token.getTokenId() : null;
            dispatchSlots.add(new DispatchSlot(device, token, tokenId, currentBatchId));
            batchId++;
        }

        while (cursor < totalMessages) {
            boolean assignedInRound = false;
            for (DispatchSlot slot : dispatchSlots) {
                if (!slot.canAccept(perDeviceBatchLimit) || cursor >= totalMessages) {
                    continue;
                }
                TaskMsg msg = pendingMessages.get(cursor);
                if (!bindTaskMessage(msg, slot.device().getDeviceId(), slot.tokenId(), slot.batchId())) {
                    log.warn("[MsgAssign] Skip task message {} because it could not transition from status {}",
                            msg.getMsgId(), msg.getStatus());
                    cursor++;
                    continue;
                }
                cursor++;
                taskManager.updateTaskMessage(task.getTid(), msg);
                pushQueue.add(msg);
                slot.incrementAssigned();
                assignedInRound = true;

                recordService.recordMessageAssignment(
                        task, slot.device(), slot.token(), msg.getMsgId(), slot.batchId(),
                        AssignmentResult.SUCCESS, "message assigned",
                        deviceManager.isLocked(slot.device().getDeviceId())
                );
            }
            if (!assignedInRound) {
                break;
            }
        }

        for (DispatchSlot slot : dispatchSlots) {
            if (slot.assignedCount() == 0) {
                deviceManager.unlockDevice(slot.device().getDeviceId());
            }
        }

        log.info("[MsgAssign] Task {} pushQueue size: {} (expected pending={})",
                task.getTid(), pushQueue.size(), totalMessages);

        if (dispatchListener != null && !pushQueue.isEmpty()) {
            dispatchListener.onTaskMsgsReady(task, List.copyOf(pushQueue));
        }
        return List.copyOf(pushQueue);
    }

    private static final class DispatchSlot {
        private final Device device;
        private final Token token;
        private final String tokenId;
        private final String batchId;
        private int assignedCount;

        private DispatchSlot(Device device, Token token, String tokenId, String batchId) {
            this.device = device;
            this.token = token;
            this.tokenId = tokenId;
            this.batchId = batchId;
        }

        private Device device() {
            return device;
        }

        private Token token() {
            return token;
        }

        private String tokenId() {
            return tokenId;
        }

        private String batchId() {
            return batchId;
        }

        private int assignedCount() {
            return assignedCount;
        }

        private boolean canAccept(int perDeviceBatchLimit) {
            return assignedCount < perDeviceBatchLimit;
        }

        private void incrementAssigned() {
            assignedCount++;
        }
    }

    private boolean isPendingDispatch(TaskMsg taskMsg) {
        return taskMsg != null && taskMsg.getStatus() == TaskMsgStatus.INIT;
    }

    private boolean bindTaskMessage(TaskMsg taskMsg, String deviceId, String tokenId, String batchId) {
        if (!taskMsg.transitionTo(TaskMsgStatus.BINDING)) {
            return false;
        }
        taskMsg.setDeviceId(deviceId);
        taskMsg.setTokenId(tokenId);
        taskMsg.setBatchId(batchId);
        return taskMsg.markAsSent();
    }

    private boolean prepareTokenForDispatch(Task task, Device device, Token token) {
        if (token == null) {
            return true;
        }

        boolean changed = false;
        String taskId = task.getTid();
        if (token.getStatus() == TokenStatus.IDLE) {
            if (!token.bindToTask(taskId)) {
                return false;
            }
            changed = true;
        }
        if (token.getStatus() == TokenStatus.RESERVED && taskId.equals(token.getLastBindTaskId())) {
            if (!token.startOccupying()) {
                return false;
            }
            changed = true;
        }

        boolean alreadySendingForTask = token.getStatus() == TokenStatus.OCCUPIED
                && taskId.equals(token.getLastBindTaskId());
        if (!alreadySendingForTask && token.getStatus() != TokenStatus.OCCUPIED) {
            return false;
        }

        return !changed || deviceManager.updateToken(device.getDeviceId(), token);
    }
}
