package com.xa.mass.kernel.result;

import java.util.Optional;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

final class ResultContextCodec {

    private final JsonMapper mapper = JsonMapper.builder().build();

    Optional<ResultContext> decode(String value) {
        if (value == null) {
            return Optional.empty();
        }
        try {
            JsonNode payload = mapper.readTree(value);
            if (payload == null || !payload.isObject()) {
                return Optional.empty();
            }
            String taskId = nonEmptyText(payload.get("taskId"));
            String messageId = nonEmptyText(payload.get("messageId"));
            String workerId = nonEmptyText(payload.get("workerId"));
            String workerGroupId = nonEmptyText(
                    payload.get("workerGroupId")
            );
            Long workerLeaseScore = positiveIntegralLong(
                    payload.get("workerLeaseScore")
            );
            if (taskId == null
                    || messageId == null
                    || workerId == null
                    || workerGroupId == null
                    || workerLeaseScore == null) {
                return Optional.empty();
            }
            return Optional.of(new ResultContext(
                    taskId,
                    messageId,
                    workerId,
                    workerGroupId,
                    workerLeaseScore
            ));
        } catch (JacksonException | IllegalArgumentException error) {
            return Optional.empty();
        }
    }

    private static String nonEmptyText(JsonNode value) {
        return value != null
                && value.isTextual()
                && !value.textValue().isEmpty()
                ? value.textValue()
                : null;
    }

    private static Long positiveIntegralLong(JsonNode value) {
        if (value == null
                || !value.isIntegralNumber()
                || !value.canConvertToLong()) {
            return null;
        }
        long decoded = value.longValue();
        return decoded > 0 ? decoded : null;
    }

    record ResultContext(
            String taskId,
            String messageId,
            String workerId,
            String workerGroupId,
            long workerLeaseScore
    ) {
    }
}
