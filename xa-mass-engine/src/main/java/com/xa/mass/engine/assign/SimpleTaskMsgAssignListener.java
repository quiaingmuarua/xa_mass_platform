package com.xa.mass.engine.assign;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.TaskMsg;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.enums.AssignmentResult;
import com.xa.mass.engine.service.AssignmentRecordService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 简单消息分配监听器实现，模拟为每个设备分配消息
 */
public class SimpleTaskMsgAssignListener implements TaskMsgAssignListener {
    private final DeviceManager deviceManager;
    private final AssignmentRecordService recordService;

    public SimpleTaskMsgAssignListener(DeviceManager deviceManager, AssignmentRecordService recordService) {
        this.deviceManager = deviceManager;
        this.recordService = recordService;
    }

    @Override
    public void onMsgAssign(Task task, List<Device> devices) {
        int batchSize = task.getBatchSize();
        int batchId = 0;
        List<TaskMsg> pushQueue = new ArrayList<>();

        System.out.println("[MsgAssign] Starting message assignment for task " + task.getTid() +
                " with " + devices.size() + " devices, batchSize=" + batchSize);
        
        for (Device device : devices) {
            for (int i = 0; i < batchSize; i++) {
                String msgId = UUID.randomUUID().toString();
                Token token = deviceManager.getToken(device.getDeviceId());
                String tokenId = token != null ? token.getTokenId() : null;
                String currentBatchId = "batch-" + batchId;

                TaskMsg msg = new TaskMsg(msgId, task.getTid(), device.getDeviceId(), tokenId, currentBatchId);
                pushQueue.add(msg);

                // 记录消息分配
                recordService.recordMessageAssignment(
                        task, device, token, msgId, currentBatchId,
                        AssignmentResult.SUCCESS, "消息分配成功"
                );
            }
            batchId++;
        }

        System.out.println("[MsgAssign] Task " + task.getTid() + " pushQueue size: " + pushQueue.size());
        // 实际可推送到 MQ 或下游队列
    }
} 