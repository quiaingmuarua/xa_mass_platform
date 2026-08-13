package com.xa.mass.workerdelivery.adapter.http;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.DeliveryCommand;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ObjectNode;

/** Strict HTTP codec owned by the remote Command Source projection. */
final class WorkerCommandGatewayHttpContract {

    private static final String DECODE_OPERATION =
            "gateway.decodeCommandResponse";
    private static final Set<String> BATCH_FIELDS = Set.of(
            "workerCommandsByWorkerId"
    );
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final WorkerDeliveryCodec codec;

    WorkerCommandGatewayHttpContract(WorkerDeliveryCodec codec) {
        this.codec = codec;
    }

    String encodeConsumeRequest(int limit) {
        if (limit <= 0) {
            throw new IllegalArgumentException(
                    "consume limit must be positive"
            );
        }
        ObjectNode payload = mapper.createObjectNode();
        payload.put("limit", limit);
        return write(payload, "Worker command consume request");
    }

    Map<String, DeliveryCommand> decodeConsumeResponse(String value) {
        try {
            JsonNode payload = mapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(BATCH_FIELDS)) {
                throw malformed("Worker command consume response");
            }
            JsonNode commands = object.get("workerCommandsByWorkerId");
            if (!(commands instanceof ObjectNode commandObject)) {
                throw malformed("Worker command consume response");
            }
            Map<String, DeliveryCommand> decoded = new LinkedHashMap<>();
            commandObject.properties().forEach(entry -> {
                if (entry.getKey().isBlank()) {
                    throw malformed("Worker command workerId");
                }
                DeliveryCommand command = codec.decodeDeliveryCommand(
                        entry.getValue().toString()
                );
                if (command == null) {
                    throw malformed("Worker command envelope");
                }
                decoded.put(entry.getKey(), command);
            });
            return Map.copyOf(decoded);
        } catch (WorkerDeliveryAdapterException error) {
            throw error;
        } catch (JacksonException | IllegalArgumentException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR,
                    DECODE_OPERATION,
                    "Worker command consume response is malformed",
                    error
            );
        }
    }

    private String write(ObjectNode payload, String type) {
        try {
            return mapper.writeValueAsString(payload);
        } catch (JacksonException error) {
            throw new IllegalStateException("Could not encode " + type, error);
        }
    }

    private static WorkerDeliveryAdapterException malformed(String type) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR,
                DECODE_OPERATION,
                type + " is malformed",
                null
        );
    }

    private static Set<String> fieldNames(ObjectNode object) {
        return new HashSet<>(object.propertyNames());
    }
}
