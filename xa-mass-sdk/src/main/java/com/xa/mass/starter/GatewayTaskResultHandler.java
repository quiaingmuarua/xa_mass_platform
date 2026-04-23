package com.xa.mass.starter;

import com.xa.mass.engine.TaskManager;
import com.xa.mass.gateway.dispatcher.handler.MassMessageHandler;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.starter.worker.WebSocketTaskMessageMapper;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;

import java.util.List;
import java.util.Map;

public class GatewayTaskResultHandler implements MassMessageHandler, TaskResultIngestChannel {

    private final TaskManager taskManager;
    private final WebSocketTaskMessageMapper messageMapper = new WebSocketTaskMessageMapper();

    public GatewayTaskResultHandler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public List<MassMessage> handle(MassMessage msg) {
        TaskResultReport report;
        try {
            report = messageMapper.toTaskResultReport(msg);
        } catch (IllegalArgumentException ex) {
            return List.of(messageMapper.buildAck(msg, 400, ex.getMessage()));
        }
        boolean handled = ingestTaskResult(
                report.getTaskId(),
                report.getMsgId(),
                report.isSuccess(),
                report.getDetail(),
                report.getErrorCode(),
                report.getOutput()
        );
        int code = handled ? 200 : 404;
        String message = handled ? "task result processed" : "task result ignored";
        return List.of(messageMapper.buildAck(msg, code, message));
    }

    @Override
    public boolean ingestTaskResult(
            String taskId,
            String msgId,
            boolean success,
            String detail,
            String errorCode,
            Map<String, Object> output
    ) {
        return taskManager.handleTaskMessageResult(
                taskId,
                msgId,
                success,
                detail,
                errorCode,
                output
        );
    }

}
