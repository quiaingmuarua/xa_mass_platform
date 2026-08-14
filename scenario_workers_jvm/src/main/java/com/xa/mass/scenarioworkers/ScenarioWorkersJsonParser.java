package com.xa.mass.scenarioworkers;

import com.xa.mass.transport.client.TextMessageReconnectPolicy;
import com.xa.mass.workerdelivery.json.Jsons;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScenarioWorkersJsonParser {

    private static final Set<String> GROUP_FIELDS = Set.of(
            "eventCodes",
            "requestTimeoutMillis",
            "reconnectPolicy"
    );
    private static final Set<String> RECONNECT_POLICY_FIELDS = Set.of(
            "maxUnstableAttempts",
            "reconnectIntervalMillis",
            "stableConnectionDurationMillis"
    );
    private ScenarioWorkersJsonParser() {
    }

    static List<ScenarioWorkerGroupConfig> parse(String configJson) {
        Map<String, Object> root = Jsons.parseObject(configJson);
        List<ScenarioWorkerGroupConfig> configs =
                new ArrayList<>(root.size());
        root.forEach((workerGroupId, rawGroup) -> {
            ScenarioWorkerGroupConfig.requireNonBlank(
                    workerGroupId,
                    "workerGroupId"
            );
            Map<String, Object> group = requireObject(
                    rawGroup,
                    "workerGroup " + workerGroupId
            );
            requireExactFields(
                    group,
                    GROUP_FIELDS,
                    "workerGroup " + workerGroupId
            );
            configs.add(new ScenarioWorkerGroupConfig(
                    workerGroupId,
                    requireStringList(group, "eventCodes"),
                    Duration.ofMillis(optionalPositiveLong(
                            group,
                            "requestTimeoutMillis",
                            10_000L
                    )),
                    parseReconnectPolicy(group)
            ));
        });
        return List.copyOf(configs);
    }

    private static TextMessageReconnectPolicy parseReconnectPolicy(
            Map<String, Object> group
    ) {
        if (!group.containsKey("reconnectPolicy")) {
            return TextMessageReconnectPolicy.defaults();
        }
        Map<String, Object> policy = requireObject(
                group.get("reconnectPolicy"),
                "reconnectPolicy"
        );
        requireExactFields(
                policy,
                RECONNECT_POLICY_FIELDS,
                "reconnectPolicy"
        );
        if (!policy.keySet().containsAll(RECONNECT_POLICY_FIELDS)) {
            throw new IllegalArgumentException(
                    "reconnectPolicy must contain all fields"
            );
        }
        return TextMessageReconnectPolicy.of(
                requirePositiveInt(
                        policy,
                        "maxUnstableAttempts"
                ),
                Duration.ofMillis(requirePositiveLong(
                        policy,
                        "reconnectIntervalMillis"
                )),
                Duration.ofMillis(requirePositiveLong(
                        policy,
                        "stableConnectionDurationMillis"
                ))
        );
    }

    private static int requirePositiveInt(
            Map<String, Object> value,
            String field
    ) {
        long parsed = requirePositiveLong(value, field);
        if (parsed > Integer.MAX_VALUE) {
            throw new IllegalArgumentException(
                    field + " must fit in a positive integer"
            );
        }
        return (int) parsed;
    }

    private static long requirePositiveLong(
            Map<String, Object> value,
            String field
    ) {
        if (!value.containsKey(field)) {
            throw new IllegalArgumentException(
                    field + " must be present"
            );
        }
        return optionalPositiveLong(value, field, 0L);
    }

    private static long optionalPositiveLong(
            Map<String, Object> value,
            String field,
            long defaultValue
    ) {
        if (!value.containsKey(field)) {
            return defaultValue;
        }
        Object raw = value.get(field);
        long parsed;
        if (raw instanceof Long) {
            parsed = (Long) raw;
        } else if (raw instanceof BigDecimal) {
            try {
                parsed = ((BigDecimal) raw).longValueExact();
            } catch (ArithmeticException error) {
                throw new IllegalArgumentException(
                        field + " must be an integer",
                        error
                );
            }
        } else {
            throw new IllegalArgumentException(
                    field + " must be an integer"
            );
        }
        if (parsed <= 0) {
            throw new IllegalArgumentException(
                    field + " must be positive"
            );
        }
        return parsed;
    }

    private static List<?> requireList(
            Map<String, Object> value,
            String field
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof List<?>)) {
            throw new IllegalArgumentException(
                    field + " must be an array"
            );
        }
        return (List<?>) raw;
    }

    private static List<String> requireStringList(
            Map<String, Object> value,
            String field
    ) {
        List<?> raw = requireList(value, field);
        List<String> result = new ArrayList<>(raw.size());
        for (Object item : raw) {
            if (!(item instanceof String) || ((String) item).isBlank()) {
                throw new IllegalArgumentException(
                        field + " must contain non-blank strings"
                );
            }
            result.add((String) item);
        }
        return List.copyOf(result);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> requireObject(
            Object value,
            String owner
    ) {
        if (!(value instanceof Map<?, ?>)) {
            throw new IllegalArgumentException(
                    owner + " must be an object"
            );
        }
        return new LinkedHashMap<>((Map<String, Object>) value);
    }

    private static void requireExactFields(
            Map<String, Object> value,
            Set<String> allowed,
            String owner
    ) {
        for (String field : value.keySet()) {
            if (!allowed.contains(field)) {
                throw new IllegalArgumentException(
                        owner + " contains unknown field " + field
                );
            }
        }
    }
}
