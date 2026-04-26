package com.xa.mass.transport.runtime;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskSharedConfig;
import com.xa.mass.engine.TaskManager;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Objects;

/**
 * Canonical runtime task-result ingest channel shared by WebSocket-adapter and pull
 * worker transport paths.
 */
public final class RuntimeTaskResultIngestChannel implements TaskResultIngestChannel {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeTaskResultIngestChannel.class);

    private final TaskManager taskManager;

    public RuntimeTaskResultIngestChannel(TaskManager taskManager) {
        this.taskManager = Objects.requireNonNull(taskManager, "taskManager");
    }

    @Override
    public boolean ingest(TaskResultReport report) {
        if (report == null) {
            return false;
        }
        String eventCode = resolveEventCode(report.getTaskId());
        logger.debug("Ingest task result via runtime channel: taskId={}, messageId={}, eventCode={}, success={}",
                report.getTaskId(), report.getMessageId(), eventCode, report.isSuccess());
        return taskManager.handleTaskMessageResult(
                report.getTaskId(),
                report.getMessageId(),
                report.isSuccess(),
                report.getDetail(),
                report.getErrorCode(),
                report.getOutput()
        );
    }

    @Override
    public boolean ingest(TransportResultEnvelope envelope) {
        if (envelope == null) {
            return false;
        }
        TaskResultReport report = envelope.getReport();
        logger.debug("Ingest task result envelope: adapterId={}, workerId={}, endpointId={}, taskId={}, messageId={}",
                envelope.getAdapterId(), envelope.getWorkerId(), envelope.getEndpointId(),
                report.getTaskId(), report.getMessageId());
        return ingest(report);
    }

    private String resolveEventCode(String taskId) {
        Task task = taskManager.getTask(taskId);
        return TaskSharedConfig.sdkEventCode(task);
    }
}
