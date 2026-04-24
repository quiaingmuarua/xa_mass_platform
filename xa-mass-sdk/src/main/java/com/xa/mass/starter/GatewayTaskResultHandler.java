package com.xa.mass.starter;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge;
import com.xa.mass.gateway.model.massMessage.MassMessage;
import com.xa.mass.starter.worker.WebSocketTaskMessageMapper;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.List;
import java.util.Map;

/**
 * WebSocket {@code TASK/step} compatibility handler that converts inbound
 * frames into transport-neutral {@link TaskResultReport} objects before
 * delegating to the task-result ingest channel.
 */
public class GatewayTaskResultHandler implements TaskStepFrameBridge, TaskResultIngestChannel {

    private static final Logger logger = LoggerFactory.getLogger(GatewayTaskResultHandler.class);

    private final TaskManager taskManager;
    private final WebSocketTaskMessageMapper messageMapper = new WebSocketTaskMessageMapper();

    public GatewayTaskResultHandler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public List<MassMessage> handleTaskStep(MassMessage msg) {
        TaskResultReport report;
        try {
            report = messageMapper.toTaskResultReport(msg);
        } catch (IllegalArgumentException ex) {
            return List.of(messageMapper.buildAck(msg, 400, ex.getMessage()));
        }
        boolean handled = ingest(report);
        int code = handled ? 200 : 404;
        String message = handled ? "task result processed" : "task result ignored";
        return List.of(messageMapper.buildAck(msg, code, message));
    }

    public boolean ingest(TaskResultReport report) {
        if (report == null) {
            return false;
        }
        String eventCode = resolveEventCode(report.getTaskId());
        logger.debug("Ingest task result via compatibility frame: taskId={}, msgId={}, eventCode={}, success={}",
                report.getTaskId(), report.getMsgId(), eventCode, report.isSuccess());
        return ingestTaskResult(
                report.getTaskId(),
                report.getMsgId(),
                report.isSuccess(),
                report.getDetail(),
                report.getErrorCode(),
                report.getOutput()
        );
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

    public String resolveEventCode(MassMessage message) {
        if (message == null) {
            return null;
        }
        try {
            TaskResultReport report = messageMapper.toTaskResultReport(message);
            return resolveEventCode(report.getTaskId());
        } catch (IllegalArgumentException ex) {
            return null;
        }
    }

    private String resolveEventCode(String taskId) {
        Task task = taskManager.getTask(taskId);
        return TaskSharedConfig.sdkEventCode(task);
    }

}
