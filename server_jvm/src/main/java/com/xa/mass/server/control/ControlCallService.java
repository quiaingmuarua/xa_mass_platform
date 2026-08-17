package com.xa.mass.server.control;

import com.xa.mass.kernel.score.WorkerScoreCore;
import com.xa.mass.kernel.score.WorkerScoreCore.WorkerScoreState;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlCallRequest;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlBatchCallResponse;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlBatchStatus;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlTargetCallResponse;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlTargetReason;
import com.xa.mass.server.api.v1.control.ControlCallHttpContract.ControlTargetStatus;
import com.xa.mass.server.control.ControlCallRegistry.BatchOutcome;
import com.xa.mass.server.control.ControlCallRegistry.ControlTarget;
import com.xa.mass.server.control.ControlCallRegistry.TargetOutcome;
import com.xa.mass.server.control.ControlCallRegistry.TargetOutcomeReason;
import com.xa.mass.server.control.ControlCallRegistry.TargetPlan;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import com.xa.mass.server.workerbinding.WorkerEndpointBinding;
import com.xa.mass.server.workerbinding.WorkerEndpointDirectory;
import com.xa.mass.server.workerbinding.WorkerTransportType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

public final class ControlCallService {

    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_CONSUME_LIMIT = 100;

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerScoreCore workerScores;
    private final WorkerBindingService workerBindings;
    private final WorkerEndpointDirectory endpoints;
    private final ControlCallRegistry registry;
    private final long defaultWaitTimeoutMillis;
    private final long maxWaitTimeoutMillis;

    public ControlCallService(
            WorkerResourceCatalog workerCatalog,
            WorkerScoreCore workerScores,
            WorkerBindingService workerBindings,
            WorkerEndpointDirectory endpoints,
            ControlCallRegistry registry,
            ControlCallProperties properties
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.workerScores = Objects.requireNonNull(
                workerScores,
                "workerScores"
        );
        this.workerBindings = Objects.requireNonNull(
                workerBindings,
                "workerBindings"
        );
        this.endpoints = Objects.requireNonNull(endpoints, "endpoints");
        this.registry = Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(properties, "properties");
        this.defaultWaitTimeoutMillis =
                properties.defaultWaitTimeoutMillis();
        this.maxWaitTimeoutMillis = properties.maxWaitTimeoutMillis();
    }

    public DeferredResult<ResponseEntity<ControlBatchCallResponse>> call(
            String adapterId,
            ControlCallRequest request
    ) {
        requireControlRequest(request);
        requireControlAdapter(adapterId);
        long timeoutMillis = resolveTimeout(request.waitTimeoutMillis());
        long deadline = System.currentTimeMillis() + timeoutMillis;

        boolean hasWorkerGroup = request.workerGroupId() != null;
        boolean hasWorkerPayloads = request.workerPayloads() != null;
        if (hasWorkerGroup != hasWorkerPayloads) {
            throw invalid(
                    "workerGroupId and workerPayloads must be provided together"
            );
        }
        if (!hasWorkerPayloads) {
            if (request.opaquePayload() == null) {
                throw invalid(
                        "opaquePayload must be present for an Adapter call"
                );
            }
            return registerBatch(
                    timeoutMillis,
                    List.of(commandPlan(
                            adapterId,
                            adapterId,
                            ControlTarget.adapter(adapterId),
                            DeliveryEndpoint.ADAPTER,
                            request.messageType(),
                            request.opaquePayload(),
                            deadline
                    ))
            );
        }
        if (request.opaquePayload() != null) {
            throw invalid(
                    "opaquePayload must be omitted for a Worker batch"
            );
        }

        String workerGroupId = request.workerGroupId();
        requireNonBlank(workerGroupId, "workerGroupId");
        Map<String, String> workerPayloads = validatedWorkerPayloads(
                request.workerPayloads()
        );
        List<String> workerIds = List.copyOf(workerPayloads.keySet());

        Map<String, WorkerDescriptor> workers;
        Map<String, WorkerScoreState> scores;
        Map<String, String> endpointIds;
        try {
            workers = Objects.requireNonNull(
                    workerCatalog.getWorkerDescriptors(
                            workerGroupId,
                            workerIds
                    ),
                    "Worker descriptor batch"
            );
            scores = Objects.requireNonNull(
                    workerScores.getScoreStates(
                            workerGroupId,
                            workerIds
                    ),
                    "Worker score batch"
            );
            endpointIds = Objects.requireNonNull(
                    workerBindings.currentEndpointManagerIds(workerIds),
                    "Worker Binding batch"
            );
        } catch (RuntimeException error) {
            throw unavailable("Could not load Worker control admission", error);
        }

        List<TargetPlan> plans = new ArrayList<>(workerIds.size());
        for (String workerId : workerIds) {
            WorkerDescriptor worker = workers.get(workerId);
            if (worker == null
                    || !workerGroupId.equals(worker.workerGroupId())) {
                plans.add(TargetPlan.rejected(
                        workerId,
                        TargetOutcomeReason.NOT_FOUND
                ));
                continue;
            }
            WorkerScoreState score = scores.get(workerId);
            if (score == null) {
                plans.add(TargetPlan.rejected(
                        workerId,
                        TargetOutcomeReason.SCORE_UNAVAILABLE
                ));
                continue;
            }
            if (score.timeMillis() != WorkerScoreCore.PAUSE_TIME_MILLIS) {
                plans.add(TargetPlan.rejected(
                        workerId,
                        TargetOutcomeReason.CONTROL_ONLY_REQUIRED
                ));
                continue;
            }
            String endpointId = endpointIds.get(workerId);
            if (endpointId == null) {
                plans.add(TargetPlan.rejected(
                        workerId,
                        TargetOutcomeReason.NOT_BOUND
                ));
                continue;
            }
            if (!adapterId.equals(endpointId)) {
                plans.add(TargetPlan.rejected(
                        workerId,
                        TargetOutcomeReason.ENDPOINT_MISMATCH
                ));
                continue;
            }
            plans.add(commandPlan(
                    workerId,
                    adapterId,
                    ControlTarget.worker(workerId),
                    DeliveryEndpoint.WORKER,
                    request.messageType(),
                    workerPayloads.get(workerId),
                    deadline
            ));
        }
        return registerBatch(timeoutMillis, plans);
    }

    public Map<String, DeliveryCommand> consume(
            String adapterId,
            int limit
    ) {
        requireControlAdapter(adapterId);
        if (limit <= 0 || limit > MAX_CONSUME_LIMIT) {
            throw invalid("consume limit must be within 1..100");
        }
        return registry.consume(
                adapterId,
                limit,
                System.currentTimeMillis()
        );
    }

    public ResultAppendCounts completeReports(
            String adapterId,
            List<DeliveryReport> reports
    ) {
        requireControlAdapter(adapterId);
        if (reports == null) {
            throw invalid("Control Result batch must be present");
        }
        if (reports.isEmpty()) {
            return new ResultAppendCounts(0, 0);
        }
        ControlCallRegistry.CompletionCounts counts =
                registry.completeReports(adapterId, reports);
        return new ResultAppendCounts(
                counts.acceptedCount(),
                counts.rejectedCount()
        );
    }

    private DeferredResult<ResponseEntity<ControlBatchCallResponse>>
            registerBatch(
                    long timeoutMillis,
                    List<TargetPlan> plans
            ) {
        String controlBatchId = UUID.randomUUID().toString();
        DeferredResult<ResponseEntity<ControlBatchCallResponse>> deferred =
                new DeferredResult<>(timeoutMillis);
        ControlCallRegistry.BatchHandle handle = registry.registerBatch(
                controlBatchId,
                plans
        );
        handle.completion().thenAccept(outcome -> deferred.setResult(
                ResponseEntity.ok(toResponse(outcome))
        ));
        deferred.onTimeout(() -> registry.timeout(controlBatchId));
        deferred.onError(ignored -> registry.cancel(controlBatchId));
        deferred.onCompletion(() -> registry.cancel(controlBatchId));
        return deferred;
    }

    private static TargetPlan commandPlan(
            String resultKey,
            String adapterId,
            ControlTarget target,
            DeliveryEndpoint destination,
            String messageType,
            String payload,
            long deadline
    ) {
        String controlCallId = UUID.randomUUID().toString();
        DeliveryCommand command = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                destination,
                messageType,
                deadline,
                payload,
                ControlCallRegistry.FORWARD_PREFIX + controlCallId
        );
        return TargetPlan.command(
                resultKey,
                controlCallId,
                adapterId,
                target,
                command
        );
    }

    private static ControlBatchCallResponse toResponse(BatchOutcome outcome) {
        Map<String, ControlTargetCallResponse> results =
                new LinkedHashMap<>();
        boolean allObserved = true;
        for (Map.Entry<String, TargetOutcome> entry
                : outcome.results().entrySet()) {
            TargetOutcome target = entry.getValue();
            ControlTargetCallResponse response;
            switch (target.status()) {
                case OBSERVED -> response =
                        ControlTargetCallResponse.observed(
                                target.outcomeCode(),
                                target.payload()
                        );
                case UNOBSERVED -> {
                    allObserved = false;
                    response = ControlTargetCallResponse.unobserved(
                            toHttpReason(target.reason())
                    );
                }
                case REJECTED -> {
                    allObserved = false;
                    response = ControlTargetCallResponse.rejected(
                            toHttpReason(target.reason())
                    );
                }
                default -> throw new IllegalStateException(
                        "Unknown Control target outcome"
                );
            }
            results.put(entry.getKey(), response);
        }
        return new ControlBatchCallResponse(
                outcome.controlBatchId(),
                allObserved
                        ? ControlBatchStatus.OBSERVED
                        : ControlBatchStatus.PARTIAL,
                results
        );
    }

    private static ControlTargetReason toHttpReason(
            TargetOutcomeReason reason
    ) {
        return switch (reason) {
            case TIMEOUT -> ControlTargetReason.TIMEOUT;
            case REPLACED -> ControlTargetReason.REPLACED;
            case SHUTDOWN -> ControlTargetReason.SHUTDOWN;
            case NOT_FOUND -> ControlTargetReason.NOT_FOUND;
            case CONTROL_ONLY_REQUIRED ->
                    ControlTargetReason.CONTROL_ONLY_REQUIRED;
            case SCORE_UNAVAILABLE ->
                    ControlTargetReason.SCORE_UNAVAILABLE;
            case NOT_BOUND -> ControlTargetReason.NOT_BOUND;
            case ENDPOINT_MISMATCH ->
                    ControlTargetReason.ENDPOINT_MISMATCH;
        };
    }

    private WorkerEndpointBinding requireControlAdapter(String adapterId) {
        requireNonBlank(adapterId, "adapterId");
        WorkerEndpointBinding endpoint = endpoints.find(adapterId);
        if (endpoint == null) {
            throw targetNotFound("Adapter was not found");
        }
        if (endpoint.transportType() == WorkerTransportType.POLLING) {
            throw invalid("Polling endpoints do not support Control Calls");
        }
        return endpoint;
    }

    private long resolveTimeout(Long requested) {
        long timeout = requested == null
                ? defaultWaitTimeoutMillis
                : requested;
        if (timeout <= 0 || timeout > maxWaitTimeoutMillis) {
            throw invalid(
                    "waitTimeoutMillis is outside the configured bound"
            );
        }
        return timeout;
    }

    private static Map<String, String> validatedWorkerPayloads(
            Map<String, String> workerPayloads
    ) {
        if (workerPayloads == null
                || workerPayloads.isEmpty()
                || workerPayloads.size() > MAX_BATCH_SIZE) {
            throw invalid(
                    "workerPayloads must contain between 1 and 100 entries"
            );
        }
        Map<String, String> validated = new LinkedHashMap<>();
        for (Map.Entry<String, String> entry : workerPayloads.entrySet()) {
            String workerId = entry.getKey();
            requireNonBlank(workerId, "workerId");
            if (entry.getValue() == null) {
                throw invalid("Worker opaquePayload must be present");
            }
            validated.put(workerId, entry.getValue());
        }
        return validated;
    }

    private static void requireControlRequest(
            ControlCallRequest request
    ) {
        if (request == null) {
            throw invalid("Control Call request must be present");
        }
        requireNonBlank(request.messageType(), "messageType");
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw invalid(name + " must be non-blank");
        }
    }

    private static ServerException invalid(String message) {
        return new ServerException(
                ServerErrorCode.INVALID_CONTROL_CALL_REQUEST,
                "controlCall.validate",
                message,
                null
        );
    }

    private static ServerException targetNotFound(String message) {
        return new ServerException(
                ServerErrorCode.CONTROL_CALL_TARGET_NOT_FOUND,
                "controlCall.requireTarget",
                message,
                null
        );
    }

    private static ServerException unavailable(
            String message,
            Throwable cause
    ) {
        return new ServerException(
                ServerErrorCode.CONTROL_CALL_UNAVAILABLE,
                "controlCall.loadTargets",
                message,
                cause
        );
    }

    public record ResultAppendCounts(
            int acceptedCount,
            int rejectedCount
    ) {
    }
}
