package com.xa.mass.server.workerdelivery.application;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.kernel.delivery.SeedResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class WorkerDeliveryService {

    private final WorkerCommandRuntime commandRuntime;
    private final SeedResultRuntime resultRuntime;

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
            throw WorkerDeliveryException.unavailable(error);
        }
    }

    public Map<String, WorkerCommandEnvelope> consumeWorkerCommands(
            String endpointManagerId,
            int limit
    ) {
        requireAdapterBatchIdentity(endpointManagerId);
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
        } catch (WorkerDeliveryException error) {
            throw error;
        } catch (RuntimeException error) {
            throw WorkerDeliveryException.unavailable(error);
        }
    }

    public void appendWorkerResult(
            String endpointManagerId,
            String workerId,
            SeedResult result
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        requireNonBlank(workerId, "workerId");
        SeedResultOutcomeClass outcomeClass =
                WorkerDeliveryProtocol.classifyOutcomeCode(
                        result.outcomeCode()
                );
        if (outcomeClass == SeedResultOutcomeClass.ADAPTER_REJECTION) {
            throw WorkerDeliveryException.invalid(
                    "Worker result outcome code must be 200 or 1xxx"
            );
        }
        appendResults(List.of(result));
    }

    public int appendAdapterResults(
            String endpointManagerId,
            List<SeedResult> results
    ) {
        requireAdapterBatchIdentity(endpointManagerId);
        if (results.isEmpty()) {
            throw WorkerDeliveryException.invalid(
                    "Adapter result batch must not be empty"
            );
        }
        appendResults(results);
        return results.size();
    }

    private void appendResults(List<SeedResult> results) {
        try {
            int accepted = resultRuntime.appendSeedResults(results);
            if (accepted != results.size()) {
                throw WorkerDeliveryException.unavailable(
                        new IllegalStateException(
                                "SeedResult batch was not fully accepted"
                        )
                );
            }
        } catch (WorkerDeliveryException error) {
            throw error;
        } catch (RuntimeException error) {
            throw WorkerDeliveryException.unavailable(error);
        }
    }

    private static void requireAdapterBatchIdentity(
            String endpointManagerId
    ) {
        requireNonBlank(endpointManagerId, "endpointManagerId");
        if (WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(
                endpointManagerId
        )) {
            throw WorkerDeliveryException.invalid(
                    "system-polling supports only point Worker access"
            );
        }
    }

    private static void requireNonBlank(String value, String name) {
        if (value == null || value.isBlank()) {
            throw WorkerDeliveryException.invalid(
                    name + " must be non-blank"
            );
        }
    }
}
