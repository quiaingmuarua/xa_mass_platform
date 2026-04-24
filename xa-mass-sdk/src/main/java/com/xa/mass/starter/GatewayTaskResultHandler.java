package com.xa.mass.starter;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.gateway.dispatcher.port.TaskStepFrameBridge;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Map;

/**
 * WebSocket {@code TASK/step} compatibility handler that converts inbound
 * frames into transport-neutral {@link TaskResultReport} objects before
 * delegating to the task-result ingest channel.
 */
public class GatewayTaskResultHandler implements TaskStepFrameBridge, TaskResultIngestChannel {

    private static final Logger logger = LoggerFactory.getLogger(GatewayTaskResultHandler.class);

    private final TaskManager taskManager;

    public GatewayTaskResultHandler(TaskManager taskManager) {
        this.taskManager = taskManager;
    }

    @Override
    public boolean handleTaskStep(TaskResultReport report) {
        return ingest(report);
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
    public boolean ingestTaskResult(String taskId,
                                    String msgId,
                                    boolean success,
                                    String detail,
                                    String errorCode,
                                    Map<String, Object> output) {
        return taskManager.handleTaskMessageResult(taskId, msgId, success, detail, errorCode, output);
    }

    private String resolveEventCode(String taskId) {
        Task task = taskManager.getTask(taskId);
        return TaskSharedConfig.sdkEventCode(task);
    }
}
