package com.xa.mass.kernel.pacer;

import java.util.Set;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

record ResultRoutingApplicationConfig(long intervalMillis) {

    public static final int PER_OUTCOME_BATCH_LIMIT = 100;
    public static final long DEFAULT_INTERVAL_MILLIS = 100;
    private static final ResultRoutingConfig ROUTING =
            new ResultRoutingConfig(PER_OUTCOME_BATCH_LIMIT);
    private static final Set<String> RESULT_ROUTING_FIELDS = Set.of(
            "intervalMillis"
    );

    public ResultRoutingApplicationConfig {
        if (intervalMillis < 1) {
            throw new IllegalArgumentException(
                    "intervalMillis must be positive"
            );
        }
    }

    public static ResultRoutingApplicationConfig fromKernelConfigJson(
            String value
    ) {
        if (value == null) {
            throw new IllegalArgumentException(
                    "Kernel Pacer config JSON must be present"
            );
        }
        try {
            JsonNode root = JsonMapper.builder().build().readTree(value);
            if (root == null || !root.isObject()) {
                throw new IllegalArgumentException(
                        "Kernel Pacer config must be a JSON object"
                );
            }
            JsonNode section = root.get("resultRouting");
            long interval = DEFAULT_INTERVAL_MILLIS;
            if (section != null) {
                if (!section.isObject()) {
                    throw new IllegalArgumentException(
                            "resultRouting must be a JSON object"
                    );
                }
                for (String field : section.propertyNames()) {
                    if (!RESULT_ROUTING_FIELDS.contains(field)) {
                        throw new IllegalArgumentException(
                                "Unknown resultRouting field: " + field
                        );
                    }
                }
                JsonNode configured = section.get("intervalMillis");
                if (configured != null) {
                    if (!configured.isIntegralNumber()
                            || !configured.canConvertToLong()) {
                        throw new IllegalArgumentException(
                                "resultRouting.intervalMillis must be "
                                        + "an integer"
                        );
                    }
                    interval = configured.longValue();
                }
            }
            return new ResultRoutingApplicationConfig(interval);
        } catch (JacksonException error) {
            throw new IllegalArgumentException(
                    "Kernel Pacer config is not valid JSON",
                    error
            );
        }
    }

    ResultRoutingConfig routing() {
        return ROUTING;
    }
}
