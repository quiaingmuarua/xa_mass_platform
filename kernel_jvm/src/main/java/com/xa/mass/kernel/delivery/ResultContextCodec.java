package com.xa.mass.kernel.delivery;

import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

public final class ResultContextCodec {

    private final JsonMapper mapper = JsonMapper.builder().build();

    public String encode(ResultContext context) {
        java.util.Objects.requireNonNull(context, "context");
        try {
            Map<String, Object> payload = new TreeMap<>();
            payload.put("taskId", context.taskId());
            payload.put("messageId", context.messageId());
            payload.put("workerId", context.workerId());
            payload.put("workerGroupId", context.workerGroupId());
            payload.put("workerLeaseScore", context.workerLeaseScore());
            return mapper.writeValueAsString(payload);
        } catch (JacksonException error) {
            throw new IllegalStateException(
                    "Result context could not be encoded",
                    error
            );
        }
    }

    public Optional<ResultContext> decode(String value) {
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

    public record ResultContext(
            String taskId,
            String messageId,
            String workerId,
            String workerGroupId,
            long workerLeaseScore
    ) {
        public ResultContext {
            requireNonBlank(taskId, "taskId");
            requireNonBlank(messageId, "messageId");
            requireNonBlank(workerId, "workerId");
            requireNonBlank(workerGroupId, "workerGroupId");
            if (workerLeaseScore <= 0) {
                throw new IllegalArgumentException(
                        "workerLeaseScore must be positive"
                );
            }
        }

        private static void requireNonBlank(String value, String name) {
            if (value == null || value.isEmpty()) {
                throw new IllegalArgumentException(
                        name + " must be non-empty"
                );
            }
        }
    }
}
