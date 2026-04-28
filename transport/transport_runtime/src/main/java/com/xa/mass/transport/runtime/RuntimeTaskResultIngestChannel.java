package com.xa.mass.transport.runtime;

import com.xa.mass.base.model.TaskMsgAttempt;
import com.xa.mass.engine.TaskResultIngestFacade;
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

    private final TaskResultIngestFacade taskResultIngestFacade;

    public RuntimeTaskResultIngestChannel(TaskResultIngestFacade taskResultIngestFacade) {
        this.taskResultIngestFacade = Objects.requireNonNull(taskResultIngestFacade, "taskResultIngestFacade");
    }

    @Override
    public boolean ingest(TaskResultReport report) {
        if (report == null) {
            return false;
        }
        logger.debug("Ingest task result via runtime channel: taskId={}, messageId={}, success={}",
                report.getTaskId(), report.getMessageId(), report.isSuccess());
        return taskResultIngestFacade.handleTaskMessageResult(
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
        TaskMsgAttempt activeAttempt = taskResultIngestFacade.getLatestActiveTaskMessageAttempt(report.getTaskId(), report.getMessageId());
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
}
