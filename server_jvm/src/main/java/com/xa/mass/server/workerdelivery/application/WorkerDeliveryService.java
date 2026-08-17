package com.xa.mass.server.workerdelivery.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReport;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryReportOutcomeClass;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.control.ControlCallService;
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

    private static final String OPAQUE_COMMAND_ENTRY_PREFIX = "entry:";

    private static final System.Logger LOGGER = System.getLogger(
            WorkerDeliveryService.class.getName()
    );

    private final WorkerCommandRuntime commandRuntime;
    private final WorkerResultRuntime resultRuntime;
    private final WorkerBindingService bindings;
    private final ControlCallService controlCalls;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    public WorkerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            WorkerResultRuntime resultRuntime,
            WorkerBindingService bindings,
            ControlCallService controlCalls
    ) {
        this.commandRuntime = commandRuntime;
        this.resultRuntime = resultRuntime;
        this.bindings = bindings;
        this.controlCalls = controlCalls;
    }

    public void verifyWorkerRoute(
            String endpointManagerId,
            String workerId
    ) {
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
            adapterCommands = controlCalls.consumeAdapterCommands(
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
        if (remaining > 0) {
            try {
                workerCommands = controlCalls.consumeWorkerCommands(
                        endpointManagerId,
                        remaining
                );
                if (workerCommands.isEmpty()) {
                    workerCommands = activeCommands(
                            commandRuntime.consumeWorkerCommands(
                                    endpointManagerId,
                                    remaining
                            )
                    );
                }
            } catch (RuntimeException error) {
                if (adapterCommands.isEmpty()) {
                    if (error instanceof ServerException serverError) {
                        throw serverError;
                    }
                    throw unavailable(operation, error);
                }
                logLowerPrioritySourceFailure(endpointManagerId, error);
                workerCommands = Map.of();
            }
        }
        return combineCommands(adapterCommands, workerCommands);
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
            Map<String, DeliveryCommand> workerCommands
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
        return Collections.unmodifiableMap(combined);
    }

    private static void logLowerPrioritySourceFailure(
            String endpointManagerId,
            RuntimeException error
    ) {
        LOGGER.log(
                System.Logger.Level.WARNING,
                "operation={0} endpointManagerId={1} failureType={2} "
                        + "message={3}",
                "workerDelivery.consumeWorkerSource",
                endpointManagerId,
                error.getClass().getName(),
                "Returning already-consumed Adapter Commands"
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
        appendResults(List.of(result), operation);
    }

    public WorkerResultAppendCounts appendAdapterResults(
            String endpointManagerId,
            List<String> encodedWorkerResults
    ) {
        String operation = "workerDelivery.appendAdapterResults";
        requireAdapterBatchIdentity(endpointManagerId, operation);
        if (encodedWorkerResults == null || encodedWorkerResults.isEmpty()) {
            throw invalid(
                    operation,
                    "Adapter result batch must not be empty"
            );
        }

        List<DeliveryReport> controlResults = new ArrayList<>();
        List<DeliveryReport> taskResults = new ArrayList<>();
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
                    controlResults.add(result);
                } else if (acceptableTaskBatchReport(
                        endpointManagerId,
                        result
                )) {
                    taskResults.add(result);
                } else {
                    rejectedCount++;
                }
            } catch (IllegalArgumentException error) {
                rejectedCount++;
            }
        }

        int acceptedCount = 0;
        if (!taskResults.isEmpty()) {
            appendResults(taskResults, operation);
            acceptedCount += taskResults.size();
        }
        if (!controlResults.isEmpty()) {
            ControlCallService.ResultAppendCounts controlCounts =
                    controlCalls.completeReports(
                            endpointManagerId,
                            controlResults
                    );
            acceptedCount += controlCounts.acceptedCount();
            rejectedCount += controlCounts.rejectedCount();
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

    private static boolean acceptableTaskBatchReport(
            String endpointManagerId,
            DeliveryReport report
    ) {
        if (report == null || report.dst() != DeliveryEndpoint.TASK) {
            return false;
        }
        DeliveryReportOutcomeClass outcome =
                WorkerDeliveryProtocol.classifyDeliveryReportOutcomeCode(
                        report.outcomeCode()
                );
        if (report.src() == DeliveryEndpoint.WORKER) {
            return outcome == DeliveryReportOutcomeClass.SUCCESS
                    || outcome == DeliveryReportOutcomeClass.WORKER_FAILURE;
        }
        return report.src() == DeliveryEndpoint.ADAPTER
                && endpointManagerId.equals(report.sourceId())
                && report.outcomeCode().startsWith("2")
                && outcome == DeliveryReportOutcomeClass.ADAPTER_REJECTION;
    }

    private void appendResults(
            List<DeliveryReport> results,
            String operation
    ) {
        try {
            int accepted = resultRuntime.appendWorkerResults(results);
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
