package com.xa.mass.server.workerdelivery;

import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.server.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandPage;
import java.util.List;
import org.springframework.stereotype.Service;

@Service
public final class WorkerDeliveryService {

    private final WorkerDeliveryRuntime runtime;

    public WorkerDeliveryService(WorkerDeliveryRuntime runtime) {
        this.runtime = runtime;
    }

    public WorkerCommandEnvelope pollWorkerCommand(
            String endpointManagerId,
            String workerId
    ) {
        try {
            WorkerCommandEnvelope command = runtime.consumeWorkerCommand(
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

    public WorkerCommandPage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    ) {
        requireAdapterBatchIdentity(endpointManagerId);
        try {
            WorkerCommandPage page = runtime.consumeWorkerCommands(
                    endpointManagerId,
                    cursor,
                    scanCount
            );
            long nowMillis = System.currentTimeMillis();
            var active = new java.util.LinkedHashMap<
                    String,
                    WorkerCommandEnvelope
                    >();
            page.workerCommandsByWorkerId().forEach((workerId, command) -> {
                if (command.executeBeforeMillis() > nowMillis) {
                    active.put(workerId, command);
                }
            });
            return new WorkerCommandPage(active, page.nextCursor());
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
        WorkerDeliveryProtocol.requireNonBlank(
                endpointManagerId,
                "endpointManagerId"
        );
        WorkerDeliveryProtocol.requireNonBlank(workerId, "workerId");
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
            int accepted = runtime.appendSeedResults(results);
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
        WorkerDeliveryProtocol.requireNonBlank(
                endpointManagerId,
                "endpointManagerId"
        );
        if (WorkerDeliveryProtocol.SYSTEM_POLLING_ENDPOINT_MANAGER_ID.equals(
                endpointManagerId
        )) {
            throw WorkerDeliveryException.invalid(
                    "system-polling supports only point Worker access"
            );
        }
    }
}
