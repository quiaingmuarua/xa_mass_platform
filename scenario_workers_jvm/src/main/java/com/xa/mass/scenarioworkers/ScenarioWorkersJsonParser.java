package com.xa.mass.scenarioworkers;

import com.xa.mass.workerdelivery.json.Jsons;
import java.math.BigDecimal;
import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

final class ScenarioWorkersJsonParser {

    private static final Set<String> GROUP_FIELDS = Set.of(
            "eventCodes",
            "endpointManagerId",
            "websocketUri",
            "requestTimeoutMillis",
            "reconnectIntervalMillis",
            "connectTimeoutMillis",
            "workers"
    );
    private static final Set<String> WORKER_FIELDS = Set.of(
            "workerId",
            "workerProperties",
            "indexedPropertyUpdates"
    );

    private ScenarioWorkersJsonParser() {
    }

    static List<ScenarioWorkerGroupConfig> parse(String configJson) {
        Map<String, Object> root = Jsons.parseObject(configJson);
        List<ScenarioWorkerGroupConfig> configs =
                new ArrayList<>(root.size());
        Set<String> workerIds = new HashSet<>();
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
            List<ScenarioWorkerConfig> workers = parseWorkers(
                    workerGroupId,
                    requireList(group, "workers"),
                    workerIds
            );
            configs.add(new ScenarioWorkerGroupConfig(
                    workerGroupId,
                    requireStringList(group, "eventCodes"),
                    requireString(group, "endpointManagerId"),
                    URI.create(requireString(group, "websocketUri")),
                    workers,
                    Duration.ofMillis(optionalPositiveLong(
                            group,
                            "requestTimeoutMillis",
                            10_000L
                    )),
                    Duration.ofMillis(optionalPositiveLong(
                            group,
                            "reconnectIntervalMillis",
                            250L
                    )),
                    Duration.ofMillis(optionalPositiveLong(
                            group,
                            "connectTimeoutMillis",
                            15_000L
                    ))
            ));
        });
        return List.copyOf(configs);
    }

    private static List<ScenarioWorkerConfig> parseWorkers(
            String workerGroupId,
            List<?> rawWorkers,
            Set<String> workerIds
    ) {
        List<ScenarioWorkerConfig> workers =
                new ArrayList<>(rawWorkers.size());
        for (int index = 0; index < rawWorkers.size(); index++) {
            String owner = "workerGroup "
                    + workerGroupId
                    + " worker "
                    + index;
            Map<String, Object> worker = requireObject(
                    rawWorkers.get(index),
                    owner
            );
            requireExactFields(worker, WORKER_FIELDS, owner);
            String workerId = requireString(worker, "workerId");
            if (!workerIds.add(workerId)) {
                throw new IllegalArgumentException(
                        "workerId must be unique: " + workerId
                );
            }
            workers.add(new ScenarioWorkerConfig(
                    workerId,
                    optionalObject(worker, "workerProperties"),
                    optionalObject(worker, "indexedPropertyUpdates")
            ));
        }
        return List.copyOf(workers);
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

    private static String requireString(
            Map<String, Object> value,
            String field
    ) {
        Object raw = value.get(field);
        if (!(raw instanceof String) || ((String) raw).isBlank()) {
            throw new IllegalArgumentException(
                    field + " must be a non-blank string"
            );
        }
        return (String) raw;
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

    private static Map<String, Object> optionalObject(
            Map<String, Object> value,
            String field
    ) {
        if (!value.containsKey(field)) {
            return Map.of();
        }
        return requireObject(value.get(field), field);
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
