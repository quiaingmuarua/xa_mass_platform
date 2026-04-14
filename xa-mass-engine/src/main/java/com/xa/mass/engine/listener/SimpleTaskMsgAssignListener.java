package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
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
    public void onMsgAssign(Task task, List<Device> devices) {
        if (devices == null || devices.isEmpty()) {
            log.info("[MsgAssign] Skip task {} because no matched devices were provided", task.getTid());
            return;
        }

        List<TaskMsg> pendingMessages = taskManager.getTaskMessages(task.getTid()).stream()
                .filter(this::isPendingDispatch)
                .collect(Collectors.toList());
        int totalMessages = pendingMessages.size();
        if (totalMessages == 0) {
            log.info("[MsgAssign] Skip task {} because there are no pending task messages to dispatch", task.getTid());
            return;
        }

        int deviceCount = devices.size();
        int baseMsgPerDevice = totalMessages / deviceCount;
        int remainder = totalMessages % deviceCount;
        int batchId = 0;
        int cursor = 0;
        List<TaskMsg> pushQueue = new ArrayList<>();

        log.info("[MsgAssign] Starting assignment for task {} with {} devices, totalMessages={}, baseMsgPerDevice={}, remainder={}",
                task.getTid(), deviceCount, totalMessages, baseMsgPerDevice, remainder);

        for (int i = 0; i < deviceCount; i++) {
            Device device = devices.get(i);
            int assignCount = baseMsgPerDevice + (i < remainder ? 1 : 0);
            String currentBatchId = "batch-" + batchId;
            Token token = deviceManager.getToken(device.getDeviceId());
            String tokenId = token != null ? token.getTokenId() : null;

            for (int j = 0; j < assignCount && cursor < totalMessages; j++) {
                TaskMsg msg = pendingMessages.get(cursor++);
                if (!bindTaskMessage(msg, device.getDeviceId(), tokenId, currentBatchId)) {
                    log.warn("[MsgAssign] Skip task message {} because it could not transition from status {}",
                            msg.getMsgId(), msg.getStatus());
                    continue;
                }
                taskManager.updateTaskMessage(task.getTid(), msg);
                pushQueue.add(msg);

                recordService.recordMessageAssignment(
                        task, device, token, msg.getMsgId(), currentBatchId,
                        AssignmentResult.SUCCESS, "message assigned",
                        deviceManager.isLocked(device.getDeviceId())
                );
            }
            batchId++;
        }

        log.info("[MsgAssign] Task {} pushQueue size: {} (expected pending={})",
                task.getTid(), pushQueue.size(), totalMessages);

        if (dispatchListener != null && !pushQueue.isEmpty()) {
            dispatchListener.onTaskMsgsReady(task, List.copyOf(pushQueue));
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
}
