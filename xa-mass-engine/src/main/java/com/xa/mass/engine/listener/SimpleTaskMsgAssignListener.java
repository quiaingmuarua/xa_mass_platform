package com.xa.mass.engine.listener;

import com.xa.mass.base.enums.assignment.AssignmentResult;
import com.xa.mass.base.model.Device;
import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsg;
import com.xa.mass.base.model.Token;
import com.xa.mass.engine.DeviceManager;
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
        int totalMessages = task.getTaskInitNumber();
        int deviceCount = devices.size();
        int baseMsgPerDevice = totalMessages / deviceCount;
        int remainder = totalMessages % deviceCount;
        int batchId = 0;
        List<TaskMsg> pushQueue = new ArrayList<>();

        log.info("[MsgAssign] Starting message assignment for task {} with {} devices, totalMessages={}, baseMsgPerDevice={}, remainder={}",
                task.getTid(), deviceCount, totalMessages, baseMsgPerDevice, remainder);

        for (int i = 0; i < deviceCount; i++) {
            Device device = devices.get(i);
            int assignCount = baseMsgPerDevice + (i < remainder ? 1 : 0); // 平均分配，余数补齐
            for (int j = 0; j < assignCount; j++) {
                String msgId = UUID.randomUUID().toString();
                Token token = deviceManager.getToken(device.getDeviceId());
                String tokenId = token != null ? token.getTokenId() : null;
                String currentBatchId = "batch-" + batchId;

                // 构建 TaskMsg，绑定目标设备和 token
                TaskMsg msg = new TaskMsg(msgId, task.getTid(), device.getDeviceId());
                msg.setDeviceId(device.getDeviceId());
                msg.setTokenId(tokenId);
                msg.setBatchId(currentBatchId);
                pushQueue.add(msg);

                // 记录消息分配
                recordService.recordMessageAssignment(
                        task, device, token, msgId, currentBatchId,
                        AssignmentResult.SUCCESS, "消息分配成功"
                );
            }
            batchId++;
        }

        log.info("[MsgAssign] Task {} pushQueue size: {} (expected totalMessages={})",
                task.getTid(), pushQueue.size(), totalMessages);
        // pushQueue 中的消息已就绪，调用方可通过回调或注入的 transporter 进一步下发
    }
} 