package com.xa.mass.worker.execution;

import com.xa.mass.workerdelivery.json.Jsons;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;

public final class WorkerCommandProcessor {

    private final String workerId;
    private final WorkerDeliveryCodec codec;
    private final Map<String, WorkerEventHandler> handlers;
    private final LongSupplier nowMillis;

    public WorkerCommandProcessor(
            String workerId,
            WorkerDeliveryCodec codec,
            Map<String, WorkerEventHandler> handlers
    ) {
        this(
                workerId,
                codec,
                handlers,
                System::currentTimeMillis
        );
    }

    WorkerCommandProcessor(
            String workerId,
            WorkerDeliveryCodec codec,
            Map<String, WorkerEventHandler> handlers,
            LongSupplier nowMillis
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        this.workerId = workerId;
        this.codec = codec;
        this.handlers = Collections.unmodifiableMap(new HashMap<>(handlers));
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
                || !(payloadValue instanceof Map<?, ?>)) {
            return ExecutionResult.failure("1400");
        }
        WorkerEventHandler handler = handlers.get((String) eventCodeValue);
        if (handler == null) {
            return ExecutionResult.failure("1404");
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> payload =
                    (Map<String, Object>) payloadValue;
            Map<String, Object> result = handler.execute(payload);
            return new ExecutionResult(
                    "200",
                    Jsons.toJson(result)
            );
        } catch (WorkerInputException error) {
            return ExecutionResult.failure("1400");
        } catch (Exception error) {
            return ExecutionResult.failure("1500");
        }
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
