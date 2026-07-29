package com.xa.mass.workerdelivery.adapter.http;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryCodec;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.SeedResult;
import com.xa.mass.workerdelivery.protocol.WorkerDeliveryProtocol.WorkerCommandEnvelope;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

final class WorkerDeliveryGatewayHttpContract {

    private static final String COMMAND_DECODE_OPERATION =
            "gateway.decodeCommandResponse";
    private static final String RESULT_DECODE_OPERATION =
            "gateway.decodeResultResponse";
    private static final Set<String> COMMAND_BATCH_FIELDS = Set.of(
            "workerCommandsByWorkerId"
    );
    private static final Set<String> ACCEPTED_FIELDS = Set.of(
            "acceptedCount"
    );
    private final ObjectMapper mapper = JsonMapper.builder().build();
    private final WorkerDeliveryCodec codec;

    WorkerDeliveryGatewayHttpContract(WorkerDeliveryCodec codec) {
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

    Map<String, WorkerCommandEnvelope> decodeConsumeResponse(String value) {
        try {
            JsonNode payload = mapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(COMMAND_BATCH_FIELDS)) {
                throw malformed(
                        COMMAND_DECODE_OPERATION,
                        "Worker command consume response"
                );
            }
            JsonNode commands = object.get("workerCommandsByWorkerId");
            if (!(commands instanceof ObjectNode commandObject)) {
                throw malformed(
                        COMMAND_DECODE_OPERATION,
                        "Worker command consume response"
                );
            }
            Map<String, WorkerCommandEnvelope> decoded =
                    new LinkedHashMap<>();
            commandObject.properties().forEach(entry -> {
                if (entry.getKey().isBlank()) {
                    throw malformed(
                            COMMAND_DECODE_OPERATION,
                            "Worker command workerId"
                    );
                }
                WorkerCommandEnvelope command = codec.decodeWorkerCommand(
                        entry.getValue().toString()
                );
                if (command == null) {
                    throw malformed(
                            COMMAND_DECODE_OPERATION,
                            "Worker command envelope"
                    );
                }
                decoded.put(entry.getKey(), command);
            });
            return Map.copyOf(decoded);
        } catch (WorkerDeliveryAdapterException error) {
            throw error;
        } catch (JacksonException | IllegalArgumentException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR,
                    COMMAND_DECODE_OPERATION,
                    "Worker command consume response is malformed",
                    error
            );
        }
    }

    String encodeResultBatch(List<SeedResult> results) {
        if (results == null || results.isEmpty()) {
            throw new IllegalArgumentException(
                    "results must not be empty"
            );
        }
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode encodedResults = payload.putArray("results");
        for (SeedResult result : results) {
            try {
                encodedResults.add(
                        mapper.readTree(codec.encodeSeedResult(result))
                );
            } catch (JacksonException error) {
                throw new IllegalStateException(
                        "Could not encode SeedResult",
                        error
                );
            }
        }
        return write(payload, "SeedResult batch");
    }

    int decodeAcceptedCount(String value) {
        try {
            JsonNode payload = mapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(ACCEPTED_FIELDS)
                    || !object.get("acceptedCount").isIntegralNumber()) {
                throw malformed(
                        RESULT_DECODE_OPERATION,
                        "SeedResult append response"
                );
            }
            return object.get("acceptedCount").intValue();
        } catch (JacksonException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR,
                    RESULT_DECODE_OPERATION,
                    "SeedResult append response is malformed",
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

    private static WorkerDeliveryAdapterException malformed(
            String operation,
            String type
    ) {
        return new WorkerDeliveryAdapterException(
                WorkerDeliveryAdapterErrorCode.GATEWAY_PROTOCOL_ERROR,
                operation,
                type + " is malformed",
                null
        );
    }

    private static Set<String> fieldNames(ObjectNode object) {
        return new HashSet<>(object.propertyNames());
    }

}
