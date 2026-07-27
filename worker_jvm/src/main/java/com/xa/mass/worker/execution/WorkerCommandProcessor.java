package com.xa.mass.worker.execution;

import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliverSeed;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.Map;
import java.util.Optional;
import java.util.function.LongSupplier;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class WorkerCommandProcessor {

    private final String workerId;
    private final WorkerDeliveryCodec codec;
    private final Map<String, WorkerEventHandler> handlers;
    private final LongSupplier nowMillis;
    private final JsonMapper json;

    public WorkerCommandProcessor(
            String workerId,
            WorkerDeliveryCodec codec,
            Map<String, WorkerEventHandler> handlers
    ) {
        this(
                workerId,
                codec,
                handlers,
                System::currentTimeMillis,
                JsonMapper.builder().build()
        );
    }

    WorkerCommandProcessor(
            String workerId,
            WorkerDeliveryCodec codec,
            Map<String, WorkerEventHandler> handlers,
            LongSupplier nowMillis,
            JsonMapper json
    ) {
        if (workerId == null || workerId.isBlank()) {
            throw new IllegalArgumentException(
                    "workerId must be non-blank"
            );
        }
        this.workerId = workerId;
        this.codec = codec;
        this.handlers = Map.copyOf(handlers);
        this.nowMillis = nowMillis;
        this.json = json;
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
        JsonNode deliveryItem;
        try {
            deliveryItem = json.readTree(value);
        } catch (JacksonException error) {
            return ExecutionResult.failure("1400");
        }
        if (deliveryItem == null || !deliveryItem.isObject()) {
            return ExecutionResult.failure("1400");
        }
        JsonNode eventCodeNode = deliveryItem.get("eventCode");
        JsonNode payload = deliveryItem.get("payload");
        if (eventCodeNode == null
                || !eventCodeNode.isTextual()
                || payload == null
                || !payload.isObject()) {
            return ExecutionResult.failure("1400");
        }
        WorkerEventHandler handler = handlers.get(eventCodeNode.textValue());
        if (handler == null) {
            return ExecutionResult.failure("1404");
        }
        try {
            JsonNode result = handler.execute(payload);
            return new ExecutionResult(
                    "200",
                    json.writeValueAsString(result)
            );
        } catch (WorkerInputException error) {
            return ExecutionResult.failure("1400");
        } catch (Exception error) {
            return ExecutionResult.failure("1500");
        }
    }

    private record ExecutionResult(
            String outcomeCode,
            String opaqueResultPayload
    ) {

        private static ExecutionResult failure(String code) {
            return new ExecutionResult(code, null);
        }
    }
}
