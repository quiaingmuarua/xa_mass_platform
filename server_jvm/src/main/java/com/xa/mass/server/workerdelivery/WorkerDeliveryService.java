package com.xa.mass.server.workerdelivery;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResultOutcomeClass;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import com.xa.mass.kernel.delivery.SeedResultRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime;
import com.xa.mass.kernel.delivery.WorkerCommandRuntime.WorkerCommandConsumePage;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

@Service
public final class WorkerDeliveryService {

    private static final Logger LOGGER = LoggerFactory.getLogger(
            WorkerDeliveryService.class
    );
    private final WorkerCommandRuntime commandRuntime;
    private final SeedResultRuntime resultRuntime;
    private final WorkerDeliveryCodec codec;

    public WorkerDeliveryService(
            WorkerCommandRuntime commandRuntime,
            SeedResultRuntime resultRuntime,
            WorkerDeliveryCodec codec
    ) {
        this.commandRuntime = commandRuntime;
        this.resultRuntime = resultRuntime;
        this.codec = codec;
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

    public WorkerCommandConsumePage consumeWorkerCommands(
            String endpointManagerId,
            String cursor,
            int scanCount
    ) {
        requireAdapterBatchIdentity(endpointManagerId);
        try {
            WorkerCommandConsumePage page =
                    commandRuntime.consumeWorkerCommands(
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
            return new WorkerCommandConsumePage(
                    active,
                    page.nextCursor()
            );
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

    public int appendWorkerResults(
            String endpointManagerId,
            List<SeedResult> results
    ) {
        requireAdapterBatchIdentity(endpointManagerId);
        if (results.isEmpty()) {
            throw WorkerDeliveryException.invalid(
                    "Worker result batch must not be empty"
            );
        }
        for (SeedResult result : results) {
            if (WorkerDeliveryProtocol.classifyOutcomeCode(
                    result.outcomeCode()
            ) == SeedResultOutcomeClass.ADAPTER_REJECTION) {
                throw WorkerDeliveryException.invalid(
                        "Worker result outcome code must be 200 or 1xxx"
                );
            }
        }
        appendResults(results);
        return results.size();
    }

    public List<SeedResult> createAdapterRejections(
            String endpointManagerId,
            Map<String, WorkerCommandEnvelope> commandsByWorkerId,
            String outcomeCode
    ) {
        requireAdapterBatchIdentity(endpointManagerId);
        if (WorkerDeliveryProtocol.classifyOutcomeCode(outcomeCode)
                != SeedResultOutcomeClass.ADAPTER_REJECTION) {
            throw WorkerDeliveryException.invalid(
                    "Adapter rejection outcome code must be 3xxx"
            );
        }
        List<SeedResult> results = new ArrayList<>();
        commandsByWorkerId.forEach((workerId, command) -> {
            DeliverSeed seed = codec.decodeDeliverSeed(command.opaqueItem());
            if (seed == null || !workerId.equals(seed.workerId())) {
                LOGGER.warn(
                        "Dropped invalid Adapter rejection commandId={} workerId={}",
                        command.commandId(),
                        workerId
                );
                return;
            }
            results.add(new SeedResult(
                    command.commandId(),
                    seed.opaqueResultContext(),
                    outcomeCode,
                    null
            ));
        });
        return List.copyOf(results);
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
