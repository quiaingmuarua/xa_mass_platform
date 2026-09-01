package com.xa.mass.server.delivery.directcall;

import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandOfferStatus;
import com.xa.mass.kernel.worker.WorkerResourceCatalog;
import com.xa.mass.kernel.worker.WorkerRuntime.WorkerDescriptor;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectCallRequest;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectCallResponse;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectCallStatus;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectTargetCallResponse;
import com.xa.mass.server.api.v1.contract.delivery.directcall.DirectCallHttpContract.DirectTargetReason;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.BatchOutcome;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.DirectTarget;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetOutcome;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetOutcomeReason;
import com.xa.mass.server.delivery.directcall.DirectCallRegistry.TargetPlan;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.worker.binding.WorkerBindingService;
import com.xa.mass.server.worker.binding.WorkerEndpointBinding;
import com.xa.mass.server.worker.binding.WorkerEndpointDirectory;
import com.xa.mass.server.worker.binding.WorkerTransportType;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.CompletionStage;
import org.springframework.http.ResponseEntity;
import org.springframework.web.context.request.async.DeferredResult;

public final class DirectCallService {

    private static final int MAX_BATCH_SIZE = 100;
    private static final int MAX_CONSUME_LIMIT = 100;
    private static final System.Logger LOGGER = System.getLogger(
            DirectCallService.class.getName()
    );

    private final WorkerResourceCatalog workerCatalog;
    private final WorkerCommandRuntime workerCommands;
    private final WorkerBindingService workerBindings;
    private final WorkerEndpointDirectory endpoints;
    private final DirectCallRegistry registry;
    private final long defaultWaitTimeoutMillis;
    private final long maxWaitTimeoutMillis;

    public DirectCallService(
            WorkerResourceCatalog workerCatalog,
            WorkerCommandRuntime workerCommands,
            WorkerBindingService workerBindings,
            WorkerEndpointDirectory endpoints,
            DirectCallRegistry registry,
            DirectCallProperties properties
    ) {
        this.workerCatalog = Objects.requireNonNull(
                workerCatalog,
                "workerCatalog"
        );
        this.workerCommands = Objects.requireNonNull(
                workerCommands,
                "workerCommands"
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

    public DeferredResult<ResponseEntity<DirectCallResponse>> call(
            String adapterId,
            DirectCallRequest request
    ) {
        requireDirectRequest(request);
        requireDirectAdapter(adapterId);
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
                            DirectTarget.adapter(adapterId),
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
        Map<String, String> endpointIds;
        try {
            workers = Objects.requireNonNull(
                    workerCatalog.getWorkerDescriptors(
                            workerGroupId,
                            workerIds
                    ),
                    "Worker descriptor batch"
            );
            endpointIds = Objects.requireNonNull(
                    workerBindings.currentEndpointManagerIds(workerIds),
                    "Worker Binding batch"
            );
        } catch (RuntimeException error) {
            throw unavailable(
                    "Could not load Worker Direct Call admission",
                    error
            );
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
                    DirectTarget.worker(workerId),
                    DeliveryEndpoint.WORKER,
                    request.messageType(),
                    workerPayloads.get(workerId),
                    deadline
            ));
        }

        DeferredResult<ResponseEntity<DirectCallResponse>> response =
                registerBatch(timeoutMillis, plans);
        offerWorkerCommands(adapterId, plans);
        return response;
    }

    /**
     * Starts one Adapter-targeted Direct Call for another Server use case.
     * The handle exposes the owner result rather than the public HTTP DTO;
     * timeout and cancellation must be returned to this owner explicitly.
     */
    public AdapterCallHandle beginAdapterCall(
            String adapterId,
            String messageType,
            String opaquePayload,
            Long requestedWaitTimeoutMillis
    ) {
        requireNonBlank(messageType, "messageType");
        if (opaquePayload == null) {
            throw invalid("opaquePayload must be present for an Adapter call");
        }
        requireDirectAdapter(adapterId);
        long timeoutMillis = resolveTimeout(requestedWaitTimeoutMillis);
        long deadline = System.currentTimeMillis() + timeoutMillis;
        TargetPlan plan = commandPlan(
                adapterId,
                adapterId,
                DirectTarget.adapter(adapterId),
                DeliveryEndpoint.ADAPTER,
                messageType,
                opaquePayload,
                deadline
        );
        String directCallId = UUID.randomUUID().toString();
        DirectCallRegistry.BatchHandle batch = registry.registerBatch(
                directCallId,
                List.of(plan)
        );
        CompletionStage<AdapterCallOutcome> completion = batch.completion()
                .thenApply(outcome -> toAdapterCallOutcome(
                        adapterId,
                        outcome
                ));
        return new AdapterCallHandle(
                directCallId,
                timeoutMillis,
                completion
        );
    }

    public void timeout(AdapterCallHandle handle) {
        Objects.requireNonNull(handle, "handle");
        registry.timeout(handle.directCallId());
    }

    public void cancel(AdapterCallHandle handle) {
        Objects.requireNonNull(handle, "handle");
        registry.cancel(handle.directCallId());
    }

    public List<DeliveryCommand> consumeAdapterCommands(
            String adapterId,
            int limit
    ) {
        requireDirectConsume(adapterId, limit);
        return registry.consumeAdapterCommands(
                adapterId,
                limit,
                System.currentTimeMillis()
        );
    }

    public ResultAppendCounts completeReports(
            String adapterId,
            List<DeliveryReport> reports
    ) {
        requireDirectAdapter(adapterId);
        if (reports == null) {
            throw invalid("Direct Result batch must be present");
        }
        if (reports.isEmpty()) {
            return new ResultAppendCounts(0, 0);
        }
        DirectCallRegistry.CompletionCounts counts =
                registry.completeReports(adapterId, reports);
        return new ResultAppendCounts(
                counts.acceptedCount(),
                counts.rejectedCount()
        );
    }

    private void offerWorkerCommands(
            String adapterId,
            List<TargetPlan> plans
    ) {
        Map<String, DeliveryCommand> commandsByWorkerId =
                new LinkedHashMap<>();
        Map<String, String> correlationsByWorkerId = new LinkedHashMap<>();
        for (TargetPlan plan : plans) {
            if (plan.target() == null
                    || plan.target().type()
                    != DirectCallRegistry.DirectTargetType.WORKER) {
                continue;
            }
            commandsByWorkerId.put(plan.target().targetId(), plan.command());
            correlationsByWorkerId.put(
                    plan.target().targetId(),
                    plan.correlationId()
            );
        }
        if (commandsByWorkerId.isEmpty()) {
            return;
        }

        Map<String, TargetOutcome> immediate = new LinkedHashMap<>();
        try {
            Map<String, WorkerCommandOfferStatus> statuses =
                    workerCommands.offerWorkerCommands(
                            adapterId,
                            commandsByWorkerId
                    );
            if (statuses == null
                    || !statuses.keySet().equals(
                            commandsByWorkerId.keySet()
                    )) {
                completeSubmissionUnknown(
                        correlationsByWorkerId,
                        immediate
                );
            } else {
                statuses.forEach((workerId, status) -> {
                    if (status == WorkerCommandOfferStatus.OCCUPIED) {
                        immediate.put(
                                correlationsByWorkerId.get(workerId),
                                TargetOutcome.rejected(
                                        TargetOutcomeReason
                                                .COMMAND_SLOT_OCCUPIED
                                )
                        );
                    } else if (status
                            != WorkerCommandOfferStatus.OFFERED) {
                        immediate.put(
                                correlationsByWorkerId.get(workerId),
                                TargetOutcome.unobserved(
                                        TargetOutcomeReason.SUBMISSION_UNKNOWN
                                )
                        );
                    }
                });
            }
        } catch (RuntimeException error) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "operation={0} adapterId={1} targetCount={2} "
                            + "failureType={3}",
                    "directCall.offerWorkerCommands",
                    adapterId,
                    commandsByWorkerId.size(),
                    error.getClass().getName()
            );
            completeSubmissionUnknown(correlationsByWorkerId, immediate);
        }
        if (!immediate.isEmpty()) {
            registry.completeTargets(immediate);
        }
    }

    private static void completeSubmissionUnknown(
            Map<String, String> correlationsByWorkerId,
            Map<String, TargetOutcome> outcomes
    ) {
        correlationsByWorkerId.values().forEach(correlationId -> outcomes.put(
                correlationId,
                TargetOutcome.unobserved(
                        TargetOutcomeReason.SUBMISSION_UNKNOWN
                )
        ));
    }

    private DeferredResult<ResponseEntity<DirectCallResponse>> registerBatch(
            long timeoutMillis,
            List<TargetPlan> plans
    ) {
        String directCallId = UUID.randomUUID().toString();
        DeferredResult<ResponseEntity<DirectCallResponse>> deferred =
                new DeferredResult<>(timeoutMillis);
        DirectCallRegistry.BatchHandle handle = registry.registerBatch(
                directCallId,
                plans
        );
        handle.completion().thenAccept(outcome -> deferred.setResult(
                ResponseEntity.ok(toResponse(outcome))
        ));
        deferred.onTimeout(() -> registry.timeout(directCallId));
        deferred.onError(ignored -> registry.cancel(directCallId));
        deferred.onCompletion(() -> registry.cancel(directCallId));
        return deferred;
    }

    private static TargetPlan commandPlan(
            String resultKey,
            String adapterId,
            DirectTarget target,
            DeliveryEndpoint destination,
            String messageType,
            String payload,
            long deadline
    ) {
        String correlationId = UUID.randomUUID().toString();
        DeliveryCommand command = DeliveryCommand.create(
                DeliveryEndpoint.SYSTEM,
                destination,
                messageType,
                deadline,
                payload,
                DirectCallRegistry.FORWARD_PREFIX + correlationId
        );
        return TargetPlan.command(
                resultKey,
                correlationId,
                adapterId,
                target,
                command
        );
    }

    private static DirectCallResponse toResponse(BatchOutcome outcome) {
        Map<String, DirectTargetCallResponse> results = new LinkedHashMap<>();
        boolean allObserved = true;
        for (Map.Entry<String, TargetOutcome> entry
                : outcome.results().entrySet()) {
            TargetOutcome target = entry.getValue();
            DirectTargetCallResponse response;
            switch (target.status()) {
                case OBSERVED -> response =
                        DirectTargetCallResponse.observed(
                                target.outcomeCode(),
                                target.payload()
                        );
                case UNOBSERVED -> {
                    allObserved = false;
                    response = DirectTargetCallResponse.unobserved(
                            toHttpReason(target.reason())
                    );
                }
                case REJECTED -> {
                    allObserved = false;
                    response = DirectTargetCallResponse.rejected(
                            toHttpReason(target.reason())
                    );
                }
                default -> throw new IllegalStateException(
                        "Unknown Direct target outcome"
                );
            }
            results.put(entry.getKey(), response);
        }
        return new DirectCallResponse(
                outcome.directCallId(),
                allObserved
                        ? DirectCallStatus.OBSERVED
                        : DirectCallStatus.PARTIAL,
                results
        );
    }

    private static AdapterCallOutcome toAdapterCallOutcome(
            String adapterId,
            BatchOutcome outcome
    ) {
        TargetOutcome target = outcome.results().get(adapterId);
        if (target == null) {
            throw new IllegalStateException(
                    "Adapter Direct Call result is missing"
            );
        }
        return new AdapterCallOutcome(
                target.status()
                        == DirectCallRegistry.TargetOutcomeStatus.OBSERVED,
                target.outcomeCode(),
                target.payload()
        );
    }

    private static DirectTargetReason toHttpReason(
            TargetOutcomeReason reason
    ) {
        return switch (reason) {
            case TIMEOUT -> DirectTargetReason.TIMEOUT;
            case SHUTDOWN -> DirectTargetReason.SHUTDOWN;
            case NOT_FOUND -> DirectTargetReason.NOT_FOUND;
            case NOT_BOUND -> DirectTargetReason.NOT_BOUND;
            case ENDPOINT_MISMATCH ->
                    DirectTargetReason.ENDPOINT_MISMATCH;
            case COMMAND_SLOT_OCCUPIED ->
                    DirectTargetReason.COMMAND_SLOT_OCCUPIED;
            case SUBMISSION_UNKNOWN ->
                    DirectTargetReason.SUBMISSION_UNKNOWN;
        };
    }

    private WorkerEndpointBinding requireDirectAdapter(String adapterId) {
        requireNonBlank(adapterId, "adapterId");
        WorkerEndpointBinding endpoint = endpoints.find(adapterId);
        if (endpoint == null) {
            throw targetNotFound("Adapter was not found");
        }
        if (endpoint.transportType() == WorkerTransportType.POLLING) {
            throw invalid("Polling endpoints do not support Direct Calls");
        }
        return endpoint;
    }

    private void requireDirectConsume(String adapterId, int limit) {
        requireDirectAdapter(adapterId);
        if (limit <= 0 || limit > MAX_CONSUME_LIMIT) {
            throw invalid("consume limit must be within 1..100");
        }
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

    private static void requireDirectRequest(DirectCallRequest request) {
        if (request == null) {
            throw invalid("Direct Call request must be present");
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
                ServerErrorCode.INVALID_DIRECT_CALL_REQUEST,
                "directCall.validate",
                message,
                null
        );
    }

    private static ServerException targetNotFound(String message) {
        return new ServerException(
                ServerErrorCode.DIRECT_CALL_TARGET_NOT_FOUND,
                "directCall.requireTarget",
                message,
                null
        );
    }

    private static ServerException unavailable(
            String message,
            Throwable cause
    ) {
        return new ServerException(
                ServerErrorCode.DIRECT_CALL_UNAVAILABLE,
                "directCall.loadTargets",
                message,
                cause
        );
    }

    public record ResultAppendCounts(
            int acceptedCount,
            int rejectedCount
    ) {
    }

    public record AdapterCallHandle(
            String directCallId,
            long timeoutMillis,
            CompletionStage<AdapterCallOutcome> completion
    ) {
        public AdapterCallHandle {
            requireNonBlank(directCallId, "directCallId");
            if (timeoutMillis <= 0) {
                throw new IllegalArgumentException(
                        "timeoutMillis must be positive"
                );
            }
            Objects.requireNonNull(completion, "completion");
        }
    }

    public record AdapterCallOutcome(
            boolean observed,
            String outcomeCode,
            String opaqueResultPayload
    ) {
    }
}
