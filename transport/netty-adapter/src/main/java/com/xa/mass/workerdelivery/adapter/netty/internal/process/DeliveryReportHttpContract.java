package com.xa.mass.workerdelivery.adapter.netty.internal.process;

import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterErrorCode;
import com.xa.mass.workerdelivery.adapter.application.WorkerDeliveryAdapterException;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.ArrayNode;
import tools.jackson.databind.node.ObjectNode;

/** Strict remote HTTP codec owned by DeliveryReportProcess. */
final class DeliveryReportHttpContract {

    private static final String DECODE_OPERATION =
            "deliveryReport.decodeRemoteResponse";
    private static final Set<String> APPEND_FIELDS = Set.of(
            "acceptedCount",
            "rejectedCount"
    );

    private final ObjectMapper mapper = JsonMapper.builder().build();

    String encodeResultBatch(List<String> encodedDeliveryReports) {
        if (encodedDeliveryReports == null || encodedDeliveryReports.isEmpty()) {
            throw new IllegalArgumentException("results must not be empty");
        }
        ObjectNode payload = mapper.createObjectNode();
        ArrayNode encodedResults = payload.putArray("results");
        for (String encodedDeliveryReport : encodedDeliveryReports) {
            if (encodedDeliveryReport == null
                    || encodedDeliveryReport.isEmpty()) {
                throw new IllegalArgumentException(
                        "encoded DeliveryReport must be non-empty"
                );
            }
            encodedResults.add(encodedDeliveryReport);
        }
        return write(payload, "DeliveryReport batch");
    }

    void requireCompleteResultResponse(String value, int expectedCount) {
        try {
            JsonNode payload = mapper.readTree(value);
            if (!(payload instanceof ObjectNode object)
                    || !fieldNames(object).equals(APPEND_FIELDS)
                    || !object.get("acceptedCount").isIntegralNumber()
                    || !object.get("acceptedCount").canConvertToInt()
                    || !object.get("rejectedCount").isIntegralNumber()
                    || !object.get("rejectedCount").canConvertToInt()) {
                throw malformed("DeliveryReport append response");
            }
            int acceptedCount = object.get("acceptedCount").intValue();
            int rejectedCount = object.get("rejectedCount").intValue();
            if (acceptedCount < 0
                    || rejectedCount < 0
                    || (long) acceptedCount + rejectedCount
                    != expectedCount) {
                throw malformed("DeliveryReport append response");
            }
        } catch (WorkerDeliveryAdapterException error) {
            throw error;
        } catch (JacksonException | ArithmeticException error) {
            throw new WorkerDeliveryAdapterException(
                    WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                    DECODE_OPERATION,
                    "DeliveryReport append response is malformed",
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
                WorkerDeliveryAdapterErrorCode.REMOTE_API_PROTOCOL_ERROR,
                DECODE_OPERATION,
                type + " is malformed",
                null
        );
    }

    private static Set<String> fieldNames(ObjectNode object) {
        return new HashSet<>(object.propertyNames());
    }
}
