package com.xa.mass.workerdelivery.adapter.http;

import com.xa.mass.workerdelivery.adapter.application.WorkerCommandPage;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
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

    private static final Set<String> PAGE_FIELDS = Set.of(
            "nextCursor",
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

    String encodeConsumeRequest(String cursor, int scanCount) {
        if (scanCount <= 0) {
            throw new IllegalArgumentException(
                    "scanCount must be positive"
            );
        }
        if (cursor != null && !isDecimal(cursor)) {
            throw new IllegalArgumentException(
                    "cursor must be a non-negative decimal cursor"
            );
        }
        ObjectNode payload = mapper.createObjectNode();
        if (cursor == null) {
            payload.putNull("cursor");
        } else {
            payload.put("cursor", cursor);
        }
        payload.put("scanCount", scanCount);
        return write(payload, "Worker command consume request");
    }

    WorkerCommandPage decodeConsumeResponse(String value) {
        try {
            JsonNode payload = mapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(PAGE_FIELDS)) {
                throw malformed("Worker command consume response");
            }
            JsonNode commands = object.get("workerCommandsByWorkerId");
            JsonNode nextCursor = object.get("nextCursor");
            if (!(commands instanceof ObjectNode commandObject)
                    || !(nextCursor.isNull() || nextCursor.isTextual())) {
                throw malformed("Worker command consume response");
            }
            Map<String, WorkerCommandEnvelope> decoded =
                    new LinkedHashMap<>();
            commandObject.properties().forEach(entry -> {
                if (entry.getKey().isBlank()) {
                    throw malformed("Worker command workerId");
                }
                WorkerCommandEnvelope command = codec.decodeWorkerCommand(
                        entry.getValue().toString()
                );
                if (command == null) {
                    throw malformed("Worker command envelope");
                }
                decoded.put(entry.getKey(), command);
            });
            return new WorkerCommandPage(
                    decoded,
                    nextCursor.isNull() ? null : nextCursor.textValue()
            );
        } catch (JacksonException | IllegalArgumentException error) {
            if (error instanceof WorkerDeliveryAdapterException adapter) {
                throw adapter;
            }
            throw new WorkerDeliveryAdapterException(
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
                throw malformed("SeedResult append response");
            }
            return object.get("acceptedCount").intValue();
        } catch (JacksonException error) {
            throw new WorkerDeliveryAdapterException(
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

    private static WorkerDeliveryAdapterException malformed(String type) {
        return new WorkerDeliveryAdapterException(type + " is malformed");
    }

    private static Set<String> fieldNames(ObjectNode object) {
        return new HashSet<>(object.propertyNames());
    }

    private static boolean isDecimal(String value) {
        if (value.isEmpty()) {
            return false;
        }
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character < '0' || character > '9') {
                return false;
            }
        }
        return true;
    }
}
