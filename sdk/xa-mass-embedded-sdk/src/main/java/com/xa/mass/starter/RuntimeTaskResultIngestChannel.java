package com.xa.mass.starter;

import com.xa.mass.base.runtime.result.TaskResultCorrelation;
import com.xa.mass.base.runtime.result.TaskResultIngestFacade;
import com.xa.mass.transport.channel.TransportResultIngressHandler;
import com.xa.mass.transport.channel.TransportResultIngressOutcome;
import com.xa.mass.transport.model.TransportResultIngressEnvelope;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;

import java.util.Objects;

/**
 * Canonical runtime result ingress handler shared by WebSocket, socket, and
 * pull-worker result paths.
 */
public final class RuntimeTaskResultIngestChannel implements TransportResultIngressHandler {

    private static final Logger logger = LoggerFactory.getLogger(RuntimeTaskResultIngestChannel.class);
    private static final String TRACE_ID_MDC_KEY = "traceId";

    private final TaskResultIngestFacade taskResultIngestFacade;
    private final TaskResultCallbackCodec callbackCodec;

    private enum IdentityValidationOutcome {
        VALID,
        ACCEPTED_NOOP
    }

    public RuntimeTaskResultIngestChannel(TaskResultIngestFacade taskResultIngestFacade) {
        this(taskResultIngestFacade, new TaskResultCallbackCodec());
    }

    RuntimeTaskResultIngestChannel(TaskResultIngestFacade taskResultIngestFacade,
                                   TaskResultCallbackCodec callbackCodec) {
        this.taskResultIngestFacade = Objects.requireNonNull(taskResultIngestFacade, "taskResultIngestFacade");
        this.callbackCodec = Objects.requireNonNull(callbackCodec, "callbackCodec");
    }

    @Override
    public TransportResultIngressOutcome handle(TransportResultIngressEnvelope envelope) {
        return handleResult(envelope).toTransportOutcome();
    }

    public ResultIngressHandleOutcome handleResult(TransportResultIngressEnvelope envelope) {
        if (envelope == null) {
            return ResultIngressHandleOutcome.RETRYABLE_FAILURE;
        }
        TaskResultCallbackCommand command;
        try {
            command = callbackCodec.decode(envelope);
        } catch (IllegalArgumentException ex) {
            logger.warn("Rejecting invalid task result ingress payload: ingressId={}, message={}",
                    envelope.getIngressId(), ex.getMessage());
            return ResultIngressHandleOutcome.PERMANENT_REJECT;
        }
        String previousTraceId = MDC.get(TRACE_ID_MDC_KEY);
        try {
            String traceId = command.traceId();
            if (traceId != null) {
                MDC.put(TRACE_ID_MDC_KEY, traceId);
            }
            logger.debug("Handle task result ingress: ingressId={}, taskId={}, messageId={}, traceId={}",
                    envelope.getIngressId(), command.taskId(), command.messageId(), traceId);
            IdentityValidationOutcome identityValidation = validateAttemptIdentity(envelope, command);
            if (identityValidation == IdentityValidationOutcome.ACCEPTED_NOOP) {
                logger.info("Ignoring task result after identity validation failure: ingressId={}, taskId={}, messageId={}, traceId={}",
                        envelope.getIngressId(), command.taskId(), command.messageId(), traceId);
                return ResultIngressHandleOutcome.HANDLED_NOOP;
            }
            boolean applied = taskResultIngestFacade.ingestTaskResult(
                    command.taskId(),
                    command.messageId(),
                    command.success(),
                    command.detail(),
                    command.errorCode(),
                    command.output()
            );
            return applied ? ResultIngressHandleOutcome.HANDLED_APPLIED : ResultIngressHandleOutcome.PERMANENT_REJECT;
        } catch (RuntimeException ex) {
            logger.error("Runtime task result ingest failed: ingressId={}, taskId={}, messageId={}",
                    envelope.getIngressId(), command.taskId(), command.messageId(), ex);
            return ResultIngressHandleOutcome.RETRYABLE_FAILURE;
        } finally {
            if (previousTraceId == null || previousTraceId.isBlank()) {
                MDC.remove(TRACE_ID_MDC_KEY);
            } else {
                MDC.put(TRACE_ID_MDC_KEY, previousTraceId);
            }
        }
    }

    private IdentityValidationOutcome validateAttemptIdentity(TransportResultIngressEnvelope envelope,
                                                              TaskResultCallbackCommand command) {
        String attemptId = command.attemptId();
        String leaseToken = command.leaseToken();
        if (attemptId == null && leaseToken == null) {
            return IdentityValidationOutcome.VALID;
        }
        TaskResultCorrelation correlation =
                taskResultIngestFacade.getResultCorrelation(command.taskId(), command.messageId());
        if (correlation == null || !correlation.activeLeasePresent()) {
            logger.warn("Result ingress identity could not be validated because no active runtime lease exists: taskId={}, messageId={}, ingressAttemptId={}, ingressLeaseToken={}, diagnostics={}",
                    command.taskId(), command.messageId(), attemptId, leaseToken, envelope.getDiagnostics());
            return IdentityValidationOutcome.ACCEPTED_NOOP;
        }
        if (leaseToken != null && !leaseToken.equals(correlation.leaseToken())) {
            logger.warn("Result ingress lease identity mismatch: taskId={}, messageId={}, ingressLeaseToken={}, activeLeaseToken={}, diagnostics={}",
                    command.taskId(), command.messageId(), leaseToken, correlation.leaseToken(), envelope.getDiagnostics());
            return IdentityValidationOutcome.ACCEPTED_NOOP;
        } else if (leaseToken != null) {
            logger.debug("Result ingress lease identity validated: taskId={}, messageId={}, leaseToken={}",
                    command.taskId(), command.messageId(), leaseToken);
        }
        if (attemptId == null) {
            return IdentityValidationOutcome.VALID;
        }
        if (correlation.projectedAttemptId() == null) {
            logger.debug("Result ingress attempt identity accepted without projected attempt id because active lease remains authoritative: taskId={}, messageId={}, ingressAttemptId={}",
                    command.taskId(), command.messageId(), attemptId);
            return IdentityValidationOutcome.VALID;
        }
        if (!attemptId.equals(correlation.projectedAttemptId())) {
            logger.warn("Result ingress attempt identity mismatch: taskId={}, messageId={}, ingressAttemptId={}, projectedAttemptId={}, diagnostics={}",
                    command.taskId(), command.messageId(), attemptId, correlation.projectedAttemptId(),
                    envelope.getDiagnostics());
            return IdentityValidationOutcome.ACCEPTED_NOOP;
        }
        logger.debug("Result ingress attempt identity validated: taskId={}, messageId={}, attemptId={}",
                command.taskId(), command.messageId(), attemptId);
        return IdentityValidationOutcome.VALID;
    }
}
