package com.xa.mass.server.workerdelivery.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommand;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerMessageEndpoint;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerResultOutcomeClass;
import com.xa.mass.kernel.delivery.WorkerResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.server.error.ServerErrorCode;
import com.xa.mass.server.error.ServerException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerDeliveryService {

    private static final System.Logger LOGGER = System.getLogger(
            WorkerDeliveryService.class.getName()
    );

    private final WorkerCommandRuntime commandRuntime;
    private final WorkerResultRuntime resultRuntime;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    public WorkerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            WorkerResultRuntime resultRuntime
    ) {
        this.commandRuntime = commandRuntime;
        this.resultRuntime = resultRuntime;
    }

    public WorkerCommand pollWorkerCommand(
            String endpointManagerId,
            String workerId
    ) {
        try {
            WorkerCommand command = commandRuntime.consumeWorkerCommand(
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

    public Map<String, WorkerCommand> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    ) {
        String operation = "workerDelivery.consumeCommands";
        requireAdapterBatchIdentity(endpointManagerId, operation);
        try {
            Map<String, WorkerCommand> commands =
                    commandRuntime.consumeWorkerCommands(
                            endpointManagerId,
                            limit
                    );
            long nowMillis = System.currentTimeMillis();
            Map<String, WorkerCommand> active =
                    new LinkedHashMap<>();
            commands.forEach((workerId, command) -> {
                if (command.executeBeforeMillis() > nowMillis) {
                    active.put(workerId, command);
                }
            });
            return Map.copyOf(active);
        } catch (ServerException error) {
            throw error;
        } catch (RuntimeException error) {
            throw unavailable(operation, error);
        }
    }

    public void appendWorkerResult(
            String endpointManagerId,
            String workerId,
            WorkerResult result
    ) {
        String operation = "workerDelivery.appendWorkerResult";
        requireNonBlank(endpointManagerId, "endpointManagerId", operation);
        requireNonBlank(workerId, "workerId", operation);
        WorkerResultOutcomeClass outcomeClass =
                WorkerDeliveryProtocol.classifyWorkerResultOutcomeCode(
                        result.outcomeCode()
        );
        if (result.dst() != WorkerMessageEndpoint.TASK
                || outcomeClass
                == WorkerResultOutcomeClass.ADAPTER_REJECTION) {
            throw invalid(
                    operation,
                    "Worker result must target TASK with outcome 200 or 1xxx"
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

        List<WorkerResult> acceptedResults = new ArrayList<>();
        int rejectedCount = 0;
        for (String encodedWorkerResult : encodedWorkerResults) {
            if (encodedWorkerResult == null
                    || encodedWorkerResult.isBlank()) {
                rejectedCount++;
                continue;
            }
            try {
                WorkerResult result = codec.decodeWorkerResult(
                        encodedWorkerResult
                );
                if (result == null
                        || result.dst() != WorkerMessageEndpoint.TASK) {
                    rejectedCount++;
                    continue;
                }
                acceptedResults.add(result);
            } catch (IllegalArgumentException error) {
                rejectedCount++;
            }
        }

        if (!acceptedResults.isEmpty()) {
            appendResults(acceptedResults, operation);
        }
        if (rejectedCount > 0) {
            LOGGER.log(
                    System.Logger.Level.WARNING,
                    "endpointManagerId={0} acceptedCount={1} "
                            + "rejectedCount={2}",
                    endpointManagerId,
                    acceptedResults.size(),
                    rejectedCount
            );
        }
        return new WorkerResultAppendCounts(
                acceptedResults.size(),
                rejectedCount
        );
    }

    private void appendResults(
            List<WorkerResult> results,
            String operation
    ) {
        try {
            int accepted = resultRuntime.appendWorkerResults(results);
            if (accepted != results.size()) {
                throw unavailable(
                        operation,
                        new IllegalStateException(
                                "WorkerResult batch was not fully accepted"
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
