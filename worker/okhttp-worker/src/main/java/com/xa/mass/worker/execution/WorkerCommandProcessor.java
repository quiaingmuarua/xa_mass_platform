package com.xa.mass.worker.execution;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class WorkerCommandProcessor {

    private final String workerId;
    private final WorkerDeliveryCodec codec;
    private final WorkerEventDefinitionManager<
            Map<String, Object>
    > eventDefinitionManager;
    private final LongSupplier nowMillis;

    public WorkerCommandProcessor(
            String workerId,
            WorkerDeliveryCodec codec,
            Map<
                    String,
                    ? extends WorkerEventDefinition<
                            ?,
                            ? extends Map<String, Object>
                    >
            > eventDefinitions
    ) {
        this(
                workerId,
                codec,
                eventDefinitions,
                System::currentTimeMillis
        );
    }

    WorkerCommandProcessor(
            String workerId,
            WorkerDeliveryCodec codec,
            Map<
                    String,
                    ? extends WorkerEventDefinition<
                            ?,
                            ? extends Map<String, Object>
                    >
            > eventDefinitions,
            LongSupplier nowMillis
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        this.workerId = workerId;
        this.codec = codec;
        this.eventDefinitionManager =
                new WorkerEventDefinitionManager<>(eventDefinitions);
        this.nowMillis = nowMillis;
    }

    public Optional<SeedResult> process(WorkerCommandEnvelope command) {
        if (nowMillis.getAsLong() >= command.executeBeforeMillis()) {
            return Optional.empty();
        }
        DeliverSeed seed = codec.decodeDeliverSeed(command.opaqueItem());
        if (seed == null) {
            throw new WorkerProtocolException(
                    "Worker command contains a malformed DeliverSeed"
            );
        }
        if (!workerId.equals(seed.workerId())) {
            throw new WorkerProtocolException(
                    "DeliverSeed belongs to a different Worker"
            );
        }

        ExecutionResult execution = execute(seed.opaqueDeliveryItem());
        return Optional.of(new SeedResult(
                command.commandId(),
                seed.opaqueResultContext(),
                execution.outcomeCode(),
                execution.opaqueResultPayload()
        ));
    }

    private ExecutionResult execute(String value) {
        Map<String, Object> deliveryItem;
        try {
            deliveryItem = Jsons.parseObject(value);
        } catch (IllegalArgumentException error) {
            return ExecutionResult.failure("1400");
        }
        Object eventCodeValue = deliveryItem.get("eventCode");
        Object payloadValue = deliveryItem.get("payload");
        if (!(eventCodeValue instanceof String)
                || ((String) eventCodeValue).isBlank()
                || !(payloadValue instanceof Map<?, ?>)) {
            return ExecutionResult.failure("1400");
        }
        try {
            Map<String, Object> result = eventDefinitionManager.dispatch(
                    (String) eventCodeValue,
                    stringKeyedMap((Map<?, ?>) payloadValue)
            );
            return new ExecutionResult(
                    "200",
                    Jsons.toJson(result)
            );
        } catch (UnknownWorkerEventException error) {
            return ExecutionResult.failure("1404");
        } catch (WorkerInputException error) {
            return ExecutionResult.failure("1400");
        } catch (Exception error) {
            return ExecutionResult.failure("1500");
        }
    }

    private static Map<String, Object> stringKeyedMap(
            Map<?, ?> value
    ) throws WorkerInputException {
        Map<String, Object> converted = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : value.entrySet()) {
            if (!(entry.getKey() instanceof String)) {
                throw new WorkerInputException(
                        "Worker event parameter keys must be strings"
                );
            }
            converted.put((String) entry.getKey(), entry.getValue());
        }
        return converted;
    }

    private static final class ExecutionResult {

        private final String outcomeCode;
        private final String opaqueResultPayload;

        private ExecutionResult(
                String outcomeCode,
                String opaqueResultPayload
        ) {
            this.outcomeCode = outcomeCode;
            this.opaqueResultPayload = opaqueResultPayload;
        }

        private String outcomeCode() {
            return outcomeCode;
        }

        private String opaqueResultPayload() {
            return opaqueResultPayload;
        }

        private static ExecutionResult failure(String code) {
            return new ExecutionResult(code, null);
        }
    }
}
