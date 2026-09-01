package com.xa.mass.server.workerdelivery.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass;
import com.xa.mass.kernel.delivery.TaskResultRuntime;
import com.xa.mass.kernel.delivery.TaskResultRuntime.TaskResultClass;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.serviceability.WorkerServiceabilityRuntime;
import com.xa.mass.server.directcall.DirectCallService;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import com.xa.mass.server.workerbinding.WorkerBindingService;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class WorkerDeliveryService {

    public static final int MAX_ADAPTER_RESULT_BATCH_SIZE = 100;

    private static final String OPAQUE_COMMAND_ENTRY_PREFIX = "entry:";
    private static final String SERVICEABILITY_EVENT =
            "platform.adapter.worker-connections.snapshot";
    private static final String SERVICEABILITY_FORWARD_PREFIX =
            "worker-serviceability:v1:";
    private static final int SERVICEABILITY_PROBE_LIMIT = 100;
    private static final long SERVICEABILITY_COMMAND_VALIDITY_MILLIS = 5_000L;

    private static final System.Logger LOGGER = System.getLogger(
            WorkerDeliveryService.class.getName()
    );

    private final WorkerCommandRuntime commandRuntime;
    private final TaskResultRuntime taskResults;
    private final WorkerBindingService bindings;
    private final DirectCallService directCalls;
    private final WorkerServiceabilityRuntime serviceability;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    public WorkerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            TaskResultRuntime taskResults,
            WorkerBindingService bindings,
            DirectCallService directCalls,
            WorkerServiceabilityRuntime serviceability
    ) {
        this.commandRuntime = commandRuntime;
        this.taskResults = taskResults;
        this.bindings = bindings;
        this.directCalls = directCalls;
        this.serviceability = serviceability;
    }

    public void verifyWorkerRoute(
            String endpointManagerId,
            String workerId
    ) {
        String operation = "workerDelivery.verifyWorkerRoute";
        requireNonBlank(endpointManagerId, "endpointManagerId", operation);
        requireNonBlank(workerId, "workerId", operation);
        bindings.requireCurrentEndpoint(endpointManagerId, workerId);
    }

    public DeliveryCommand pollWorkerCommand(
            String endpointManagerId,
            String workerId
    ) {
        requirePointBinding(endpointManagerId, workerId);
        try {
            DeliveryCommand command = commandRuntime.consumeWorkerCommand(
                    endpointManagerId,
                    workerId
            );
            if (command == null
                    || command.executeBeforeMillis()
                    <= System.currentTimeMillis()) {
                return null;
            }
            return command;
        } catch (RuntimeException error) {
            throw unavailable("workerDelivery.pollCommand", error);
        }
    }

    public Map<String, DeliveryCommand> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    ) {
        String operation = "workerDelivery.consumeCommands";
        requireAdapterBatchIdentity(endpointManagerId, operation);
        List<DeliveryCommand> adapterCommands;
        try {
            adapterCommands = directCalls.consumeAdapterCommands(
                    endpointManagerId,
                    limit
            );
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }

        int remaining = limit - adapterCommands.size();
        Map<String, DeliveryCommand> workerCommands = Map.of();
        RuntimeException workerSourceFailure = null;
        if (remaining > 0) {
            try {
                workerCommands = activeCommands(
                        commandRuntime.consumeWorkerCommands(
                                endpointManagerId,
                                remaining
                        )
                );
            } catch (RuntimeException error) {
                workerSourceFailure = error;
                workerCommands = Map.of();
            }
        }
        remaining -= workerCommands.size();
        DeliveryCommand serviceabilityCommand = remaining > 0
                ? consumeServiceabilityCommand(endpointManagerId)
                : null;
        if (workerSourceFailure != null) {
            if (adapterCommands.isEmpty()
                    && serviceabilityCommand == null) {
                if (workerSourceFailure instanceof ServerException serverError) {
                    throw serverError;
                }
                throw unavailable(operation, workerSourceFailure);
            }
            logLowerPriorityFailure(
                    "workerDelivery.consumeWorkerCommands",
                    endpointManagerId,
                    workerSourceFailure,
                    "Continuing with already-consumed higher-priority Commands"
            );
        }
        return combineCommands(
                adapterCommands,
                workerCommands,
                serviceabilityCommand
        );
    }

    private DeliveryCommand consumeServiceabilityCommand(
            String endpointManagerId
    ) {
        List<String> workerIds;
        try {
            workerIds = serviceability.consumeProbeRequests(
                    endpointManagerId,
                    SERVICEABILITY_PROBE_LIMIT
            );
        } catch (RuntimeException error) {
            logLowerPriorityFailure(
                    "workerDelivery.consumeServiceabilityCommand",
                    endpointManagerId,
                    error,
                    "Continuing without a Serviceability Command"
            );
            return null;
        }
        if (workerIds.isEmpty()) {
            return null;
        }
        long checkStartedAtMillis = System.currentTimeMillis();
        return DeliveryCommand.create(
                DeliveryEndpoint.KERNEL,
                DeliveryEndpoint.ADAPTER,
                SERVICEABILITY_EVENT,
                Math.addExact(
                        checkStartedAtMillis,
                        SERVICEABILITY_COMMAND_VALIDITY_MILLIS
                ),
                Jsons.toJson(Map.of("workerIds", workerIds)),
                SERVICEABILITY_FORWARD_PREFIX + checkStartedAtMillis
        );
    }

    private static Map<String, DeliveryCommand> activeCommands(
            Map<String, DeliveryCommand> commands
    ) {
        long nowMillis = System.currentTimeMillis();
        Map<String, DeliveryCommand> active = new LinkedHashMap<>();
        commands.forEach((workerId, command) -> {
            if (command.executeBeforeMillis() > nowMillis) {
                active.put(workerId, command);
            }
        });
        return active;
    }

    private static Map<String, DeliveryCommand> combineCommands(
            List<DeliveryCommand> adapterCommands,
            Map<String, DeliveryCommand> workerCommands,
            DeliveryCommand serviceabilityCommand
    ) {
        Map<String, DeliveryCommand> combined = new LinkedHashMap<>();
        Set<String> occupied = new HashSet<>(workerCommands.keySet());
        int ordinal = 0;
        for (DeliveryCommand command : adapterCommands) {
            String entryKey;
            do {
                entryKey = OPAQUE_COMMAND_ENTRY_PREFIX + ordinal++;
            } while (occupied.contains(entryKey));
            occupied.add(entryKey);
            combined.put(entryKey, command);
        }
        combined.putAll(workerCommands);
        if (serviceabilityCommand != null) {
            String entryKey;
            do {
                entryKey = OPAQUE_COMMAND_ENTRY_PREFIX + ordinal++;
            } while (occupied.contains(entryKey));
            combined.put(entryKey, serviceabilityCommand);
        }
        return Collections.unmodifiableMap(combined);
    }

    private static void logLowerPriorityFailure(
            String operation,
            String endpointManagerId,
            RuntimeException error,
            String disposition
    ) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "operation={0} endpointManagerId={1} failureType={2} "
                        + "disposition={3}",
                operation,
                endpointManagerId,
                error.getClass().getName(),
                disposition
        );
    }

    public void appendWorkerResult(
            String endpointManagerId,
            String workerId,
            DeliveryReport result
    ) {
        String operation = "workerDelivery.appendWorkerResult";
        requirePointBinding(endpointManagerId, workerId);
        DeliveryReportOutcomeClass outcomeClass =
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode(
                        result.outcomeCode()
        );
        if (result.src() != DeliveryEndpoint.WORKER
                || !workerId.equals(result.sourceId())
                || result.dst() != DeliveryEndpoint.TASK
                || outcomeClass == DeliveryReportOutcomeClass.ADAPTER_REJECTION) {
            throw invalid(
                    operation,
                    "Worker result must target TASK with outcome 200 "
                            + "or a Worker-owned 3... code"
            );
        }
        appendTaskResults(
                outcomeClass == DeliveryReportOutcomeClass.SUCCESS
                        ? TaskResultClass.SUCCESS
                        : TaskResultClass.FAILURE,
                List.of(result),
                operation
        );
    }

    public WorkerResultAppendCounts appendAdapterResults(
            String endpointManagerId,
            List<String> encodedWorkerResults
    ) {
        String operation = "workerDelivery.appendAdapterResults";
        requireAdapterBatchIdentity(endpointManagerId, operation);
        if (encodedWorkerResults == null
                || encodedWorkerResults.isEmpty()
                || encodedWorkerResults.size()
                > MAX_ADAPTER_RESULT_BATCH_SIZE) {
            throw invalid(
                    operation,
                    "Adapter result batch must contain 1..100 Reports"
            );
        }

        List<DeliveryReport> directCallResults = new ArrayList<>();
        List<DeliveryReport> kernelResults = new ArrayList<>();
        List<DeliveryReport> successfulTaskResults = new ArrayList<>();
        List<DeliveryReport> failedTaskResults = new ArrayList<>();
        int rejectedCount = 0;
        for (String encodedWorkerResult : encodedWorkerResults) {
            if (encodedWorkerResult == null
                    || encodedWorkerResult.isBlank()) {
                rejectedCount++;
                continue;
            }
            try {
                DeliveryReport result = codec.decodeDeliveryReport(
                        encodedWorkerResult
                );
                if (result == null) {
                    rejectedCount++;
                } else if (result.dst() == DeliveryEndpoint.SYSTEM) {
                    directCallResults.add(result);
                } else if (result.dst() == DeliveryEndpoint.KERNEL
                        && result.src() == DeliveryEndpoint.ADAPTER
                        && endpointManagerId.equals(result.sourceId())) {
                    kernelResults.add(result);
                } else {
                    TaskResultClass resultClass = taskResultClass(
                            endpointManagerId,
                            result
                    );
                    if (resultClass == TaskResultClass.SUCCESS) {
                        successfulTaskResults.add(result);
                    } else if (resultClass == TaskResultClass.FAILURE) {
                        failedTaskResults.add(result);
                    } else {
                        rejectedCount++;
                    }
                }
            } catch (IllegalArgumentException error) {
                rejectedCount++;
            }
        }

        int acceptedCount = 0;
        if (!successfulTaskResults.isEmpty()) {
            appendTaskResults(
                    TaskResultClass.SUCCESS,
                    successfulTaskResults,
                    operation
            );
            acceptedCount += successfulTaskResults.size();
        }
        if (!failedTaskResults.isEmpty()) {
            appendTaskResults(
                    TaskResultClass.FAILURE,
                    failedTaskResults,
                    operation
            );
            acceptedCount += failedTaskResults.size();
        }
        if (!directCallResults.isEmpty()) {
            DirectCallService.ResultAppendCounts directCounts =
                    directCalls.completeReports(
                            endpointManagerId,
                            directCallResults
                    );
            acceptedCount += directCounts.acceptedCount();
            rejectedCount += directCounts.rejectedCount();
        }
        if (!kernelResults.isEmpty()) {
            try {
                int accepted = serviceability.appendAdapterEvidenceResults(
                        kernelResults
                );
                acceptedCount += accepted;
                rejectedCount += kernelResults.size() - accepted;
            } catch (RuntimeException error) {
                rejectedCount += kernelResults.size();
                logLowerPriorityFailure(
                        "workerDelivery.appendAdapterEvidenceResults",
                        endpointManagerId,
                        error,
                        "Rejecting Adapter evidence Report count="
                                + kernelResults.size()
                );
            }
        }
        if (rejectedCount > 0) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "endpointManagerId={0} acceptedCount={1} "
                            + "rejectedCount={2}",
                    endpointManagerId,
                    acceptedCount,
                    rejectedCount
            );
        }
        return new WorkerResultAppendCounts(
                acceptedCount,
                rejectedCount
        );
    }

    private static TaskResultClass taskResultClass(
            String endpointManagerId,
            DeliveryReport report
    ) {
        if (report == null || report.dst() != DeliveryEndpoint.TASK) {
            return null;
        }
        DeliveryReportOutcomeClass outcome =
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode(
                        report.outcomeCode()
                );
        if (report.src() == DeliveryEndpoint.WORKER) {
            if (outcome == DeliveryReportOutcomeClass.SUCCESS) {
                return TaskResultClass.SUCCESS;
            }
            if (outcome == DeliveryReportOutcomeClass.WORKER_FAILURE) {
                return TaskResultClass.FAILURE;
            }
            return null;
        }
        return report.src() == DeliveryEndpoint.ADAPTER
                && endpointManagerId.equals(report.sourceId())
                && report.outcomeCode().startsWith("2")
                && outcome == DeliveryReportOutcomeClass.ADAPTER_REJECTION
                ? TaskResultClass.FAILURE
                : null;
    }

    private void appendTaskResults(
            TaskResultClass resultClass,
            List<DeliveryReport> results,
            String operation
    ) {
        try {
            int accepted = taskResults.appendTaskResults(
                    resultClass,
                    results
            );
            if (accepted != results.size()) {
                throw unavailable(
                        operation,
                        new IllegalStateException(
                                "DeliveryReport batch was not fully accepted"
                        )
                );
            }
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }
    }

    private static void requireAdapterBatchIdentity(
            String endpointManagerId,
            String operation
    ) {
        requireNonBlank(
                endpointManagerId,
                "endpointManagerId",
                operation
        );
        if (WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(
                endpointManagerId
        )) {
            throw invalid(
                    operation,
                    "system-polling supports only point Worker access"
            );
        }
    }

    private void requirePointBinding(
            String endpointManagerId,
            String workerId
    ) {
        String operation = "workerDelivery.requirePointBinding";
        requireNonBlank(endpointManagerId, "endpointManagerId", operation);
        requireNonBlank(workerId, "workerId", operation);
        if (!WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(
                endpointManagerId
        )) {
            throw invalid(
                    operation,
                    "Point Worker access requires system-polling"
            );
        }
        bindings.requireCurrentEndpoint(endpointManagerId, workerId);
    }

    private static void requireNonBlank(
            String value,
            String name,
            String operation
    ) {
        if (value == null || value.isBlank()) {
            throw invalid(
                    operation,
                    name + " must be non-blank"
            );
        }
    }

    private static ServerException invalid(
            String operation,
            String message
    ) {
        return new ServerException(
                ServerErrorCode.INVALID_WORKER_DELIVERY_REQUEST,
                operation,
                message,
                null
        );
    }

    private static ServerException unavailable(
            String operation,
            Throwable cause
    ) {
        return new ServerException(
                ServerErrorCode.WORKER_DELIVERY_UNAVAILABLE,
                operation,
                null,
                cause
        );
    }

    public record WorkerResultAppendCounts(
            int acceptedCount,
            int rejectedCount
    ) {
    }
}
