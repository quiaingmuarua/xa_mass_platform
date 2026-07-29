package com.xa.mass.server.workerdelivery.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultSource;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.kernel.delivery.SeedResultRuntime;
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
    private final SeedResultRuntime resultRuntime;
    private final WorkerDeliveryCodec codec = new WorkerDeliveryCodec();

    public WorkerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            SeedResultRuntime resultRuntime
    ) {
        this.commandRuntime = commandRuntime;
        this.resultRuntime = resultRuntime;
    }

    public WorkerCommandEnvelope pollWorkerCommand(
            String endpointManagerId,
            String workerId
    ) {
        try {
            WorkerCommandEnvelope command = commandRuntime.consumeWorkerCommand(
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

    public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    ) {
        String operation = "workerDelivery.consumeCommands";
        requireAdapterBatchIdentity(endpointManagerId, operation);
        try {
            Map<String, WorkerCommandEnvelope> commands =
                    commandRuntime.consumeWorkerCommands(
                            endpointManagerId,
                            limit
                    );
            long nowMillis = System.currentTimeMillis();
            Map<String, WorkerCommandEnvelope> active =
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
            SeedResult result
    ) {
        String operation = "workerDelivery.appendWorkerResult";
        requireNonBlank(endpointManagerId, "endpointManagerId", operation);
        requireNonBlank(workerId, "workerId", operation);
        SeedResultOutcomeClass outcomeClass =
                WorkerDeliveryProtocol.classifyOutcomeCode(
                        result.outcomeCode()
        );
        if (outcomeClass == SeedResultOutcomeClass.ADAPTER_REJECTION) {
            throw invalid(
                    operation,
                    "Worker result outcome code must be 200 or 1xxx"
            );
        }
        appendResults(List.of(result), operation);
    }

    public SeedResultAppendCounts appendAdapterResults(
            String endpointManagerId,
            SeedResultSource source,
            List<String> encodedSeedResults
    ) {
        String operation = "workerDelivery.appendAdapterResults";
        requireAdapterBatchIdentity(endpointManagerId, operation);
        if (source == null) {
            throw invalid(operation, "SeedResult source must be present");
        }
        if (encodedSeedResults == null || encodedSeedResults.isEmpty()) {
            throw invalid(
                    operation,
                    "Adapter result batch must not be empty"
            );
        }

        List<SeedResult> acceptedResults = new ArrayList<>();
        int rejectedCount = 0;
        for (String encodedSeedResult : encodedSeedResults) {
            if (encodedSeedResult == null
                    || encodedSeedResult.isBlank()) {
                rejectedCount++;
                continue;
            }
            try {
                SeedResult result = codec.decodeSeedResult(
                        encodedSeedResult
                );
                if (result == null || !sourceAllows(source, result)) {
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
                    "endpointManagerId={0} source={1} "
                            + "acceptedCount={2} rejectedCount={3}",
                    endpointManagerId,
                    source,
                    acceptedResults.size(),
                    rejectedCount
            );
        }
        return new SeedResultAppendCounts(
                acceptedResults.size(),
                rejectedCount
        );
    }

    private static boolean sourceAllows(
            SeedResultSource source,
            SeedResult result
    ) {
        SeedResultOutcomeClass outcomeClass =
                WorkerDeliveryProtocol.classifyOutcomeCode(
                        result.outcomeCode()
                );
        return source == SeedResultSource.WORKER
                ? outcomeClass == SeedResultOutcomeClass.SUCCESS
                || outcomeClass
                == SeedResultOutcomeClass.WORKER_FAILURE
                : outcomeClass
                == SeedResultOutcomeClass.ADAPTER_REJECTION;
    }

    private void appendResults(
            List<SeedResult> results,
            String operation
    ) {
        try {
            int accepted = resultRuntime.appendSeedResults(results);
            if (accepted != results.size()) {
                throw unavailable(
                        operation,
                        new IllegalStateException(
                                "SeedResult batch was not fully accepted"
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

    public record SeedResultAppendCounts(
            int acceptedCount,
            int rejectedCount
    ) {
    }
}
