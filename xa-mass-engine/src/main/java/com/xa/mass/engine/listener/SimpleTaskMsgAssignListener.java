package com.xa.mass.engine.listener;

import com.xa.mass.engine.model.Device;
import com.xa.mass.engine.model.Token;
import com.xa.mass.engine.model.TaskMsg;
import com.xa.mass.engine.model.task.Task;
import com.xa.mass.engine.DeviceManager;
import com.xa.mass.engine.model.enums.AssignmentResult;
import com.xa.mass.engine.service.AssignmentRecordService;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * 简单消息分配监听器实现，模拟为每个设备分配消息
 */
public class SimpleTaskMsgAssignListener implements TaskMsgAssignListener {
    private static final Logger log = LoggerFactory.getLogger(SimpleTaskMsgAssignListener.class);
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

        log.info("[MsgAssign] Starting message assignment for task {} with {} devices, batchSize={}", task.getTid(), devices.size(), batchSize);
        
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

        log.info("[MsgAssign] Task {} pushQueue size: {}", task.getTid(), pushQueue.size());
        // 实际可推送到 MQ 或下游队列
    }
} 