package com.xa.mass.transport.runtime;

import com.xa.mass.base.model.Task;
import com.xa.mass.base.model.TaskMsgAttempt;
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
        logger.debug("Ingest task result envelope: adapterId={}, workerId={}, endpointId={}, attemptId={}, taskId={}, messageId={}",
                envelope.getAdapterId(), envelope.getWorkerId(), envelope.getEndpointId(), envelope.getAttemptId(),
                report.getTaskId(), report.getMessageId());
        validateAttemptIdentity(envelope, report);
        return ingest(report);
    }

    private void validateAttemptIdentity(TransportResultEnvelope envelope, TaskResultReport report) {
        String attemptId = envelope.getAttemptId();
        if (attemptId == null) {
            return;
        }
        TaskMsgAttempt activeAttempt = taskManager.getLatestActiveTaskMessageAttempt(report.getTaskId(), report.getMessageId());
        if (activeAttempt == null) {
            logger.warn("Result envelope attempt identity could not be validated because no active attempt exists: taskId={}, messageId={}, envelopeAttemptId={}, adapterId={}, workerId={}, endpointId={}",
                    report.getTaskId(), report.getMessageId(), attemptId, envelope.getAdapterId(), envelope.getWorkerId(), envelope.getEndpointId());
            return;
        }
        if (!attemptId.equals(activeAttempt.getAttemptId())) {
            logger.warn("Result envelope attempt identity mismatch: taskId={}, messageId={}, envelopeAttemptId={}, activeAttemptId={}, adapterId={}, workerId={}, endpointId={}",
                    report.getTaskId(), report.getMessageId(), attemptId, activeAttempt.getAttemptId(),
                    envelope.getAdapterId(), envelope.getWorkerId(), envelope.getEndpointId());
            return;
        }
        logger.debug("Result envelope attempt identity validated: taskId={}, messageId={}, attemptId={}, adapterId={}, workerId={}, endpointId={}",
                report.getTaskId(), report.getMessageId(), attemptId, envelope.getAdapterId(), envelope.getWorkerId(), envelope.getEndpointId());
    }

    private String resolveEventCode(String taskId) {
        Task task = taskManager.getTask(taskId);
        return TaskSharedConfig.sdkEventCode(task);
    }
}
