package com.xa.mass.transport.runtime;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.transport.channel.TaskResultIngestChannel;
import com.xa.mass.transport.model.TaskResultReport;
import com.xa.mass.transport.model.TransportResultEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;

/**
 * Canonical runtime task-result ingest channel shared by WebSocket-adapter and pull
 * worker transport paths.
 */
public final class RuntimeTaskResultIngestChannel implements TaskResultIngestChannel {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeTaskResultIngestChannel.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

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
        return taskResultIngestFacade.ingestTaskResult(
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
        String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);
        try {
            if (envelope.getTraceId() != null) {
                MDC.put(TRACE_ID_MDC_KEY, envelope.getTraceId());
            }
            TaskResultReport report = envelope.getReport();
            logger.debug("Ingest task result envelope: adapterId={}, routeKey={}, attemptId={}, taskId={}, messageId={}, traceId={}",
                    envelope.getAdapterId(), envelope.getRouteKey(), envelope.getAttemptId(),
                    report.getTaskId(), report.getMessageId(), envelope.getTraceId());
            validateAttemptIdentity(envelope, report);
            return ingest(report);
        } finally {
            if (previousTraceId == null || previousTraceId.isBlank()) {
                MDC.remove(TRACE_ID_MDC_KEY);
            } else {
                MDC.put(TRACE_ID_MDC_KEY, previousTraceId);
            }
        }
    }

    private void validateAttemptIdentity(TransportResultEnvelope envelope, TaskResultReport report) {
        String attemptId = envelope.getAttemptId();
        String leaseToken = envelope.getLeaseToken();
        if (attemptId == null && leaseToken == null) {
            return;
        }
        TaskResultCorrelation correlation = taskResultIngestFacade.getResultCorrelation(report.getTaskId(), report.getMessageId());
        if (correlation == null || !correlation.activeLeasePresent()) {
            logger.warn("Result envelope identity could not be validated because no active runtime lease exists: taskId={}, messageId={}, envelopeAttemptId={}, envelopeLeaseToken={}, adapterId={}, routeKey={}",
                    report.getTaskId(), report.getMessageId(), attemptId, leaseToken, envelope.getAdapterId(), envelope.getRouteKey());
            return;
        }
        if (leaseToken != null && !leaseToken.equals(correlation.leaseToken())) {
            logger.warn("Result envelope lease identity mismatch: taskId={}, messageId={}, envelopeLeaseToken={}, activeLeaseToken={}, adapterId={}, routeKey={}",
                    report.getTaskId(), report.getMessageId(), leaseToken, correlation.leaseToken(),
                    envelope.getAdapterId(), envelope.getRouteKey());
        } else if (leaseToken != null) {
            logger.debug("Result envelope lease identity validated: taskId={}, messageId={}, leaseToken={}, adapterId={}, routeKey={}",
                    report.getTaskId(), report.getMessageId(), leaseToken, envelope.getAdapterId(), envelope.getRouteKey());
        }
        if (attemptId == null) {
            return;
        }
        if (correlation.projectedAttemptId() == null) {
            logger.warn("Result envelope attempt identity could not be validated because no projected attempt id exists: taskId={}, messageId={}, envelopeAttemptId={}, adapterId={}, routeKey={}",
                    report.getTaskId(), report.getMessageId(), attemptId, envelope.getAdapterId(), envelope.getRouteKey());
            return;
        }
        if (!attemptId.equals(correlation.projectedAttemptId())) {
            logger.warn("Result envelope attempt identity mismatch: taskId={}, messageId={}, envelopeAttemptId={}, projectedAttemptId={}, adapterId={}, routeKey={}",
                    report.getTaskId(), report.getMessageId(), attemptId, correlation.projectedAttemptId(),
                    envelope.getAdapterId(), envelope.getRouteKey());
            return;
        }
        logger.debug("Result envelope attempt identity validated: taskId={}, messageId={}, attemptId={}, adapterId={}, routeKey={}",
                report.getTaskId(), report.getMessageId(), attemptId, envelope.getAdapterId(), envelope.getRouteKey());
    }
}
