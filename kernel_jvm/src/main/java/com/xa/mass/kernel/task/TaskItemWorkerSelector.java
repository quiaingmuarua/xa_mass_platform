package com.xa.mass.kernel.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.LinkedHashMap;
import java.util.Collections;

/** One immutable query. Only ANY and explicit identity semantics belong to Kernel. */
public record TaskItemWorkerSelector(Map<String, Object> expression) {

    private static final int MAX_TARGET_WORKERS = 100;
    private static final String WORKER_ID = "workerId";

    @SuppressWarnings("unchecked")
    public TaskItemWorkerSelector {
        if (expression == null || expression.size() > 100) {
            throw new IllegalArgumentException("workerSelector must be an empty object, identity list or at most 100 property conditions");
        }
        if (expression.isEmpty()) {
            expression = Map.of();
        } else {
            if (expression.containsKey(WORKER_ID) && expression.size() != 1) {
                throw new IllegalArgumentException("workerId cannot be combined with property conditions");
            }
            for (Map.Entry<?, ?> entry : expression.entrySet()) {
            if (!(entry.getKey() instanceof String binding) || binding.isBlank()) {
                throw new IllegalArgumentException("selector requires a non-blank binding");
            }
            if (WORKER_ID.equals(binding)) {
                if (!(entry.getValue() instanceof List<?> ids) || ids.isEmpty()
                        || ids.size() > MAX_TARGET_WORKERS
                        || ids.stream().anyMatch(id -> !(id instanceof String text) || text.isBlank())
                        || new HashSet<>(ids).size() != ids.size()) {
                    throw new IllegalArgumentException("workerId selector requires 1..100 unique non-blank identities");
                }
            }
            if (!WORKER_ID.equals(binding)) {
                Object query = entry.getValue();
                if (query instanceof Map<?, ?> map) {
                    if (map.isEmpty()) throw new IllegalArgumentException("property query must be nonempty");
                } else if (!(query instanceof List<?> list) || list.isEmpty()
                        || list.stream().anyMatch(item -> !(item instanceof String))) {
                    throw new IllegalArgumentException("property query must be an object or string parameters");
                }
            }
            }
            expression = (Map<String, Object>) snapshot(expression, 0);
        }
    }

    @SuppressWarnings("unchecked")
    public static TaskItemWorkerSelector parse(Map<?, ?> expression) {
        // The constructor validates every raw HTTP/storage entry before immutable capture.
        return new TaskItemWorkerSelector((Map<String, Object>) expression);
    }

    public boolean isAny() {
        return expression.isEmpty();
    }

    public boolean hasExplicitWorkerIds() {
        return expression.containsKey(WORKER_ID);
    }

    @SuppressWarnings("unchecked")
    public List<String> targetWorkerIds() {
        if (!hasExplicitWorkerIds()) {
            throw new IllegalStateException("selector does not contain explicit Worker identities");
        }
        return (List<String>) expression.get(WORKER_ID);
    }

    private static Object snapshot(Object value, int depth) {
        if (depth > 4) throw new IllegalArgumentException("selector structure is too deep");
        if (value instanceof Map<?, ?> map) {
            if (map.size() > 100) throw new IllegalArgumentException("selector object is too large");
            var copy = new LinkedHashMap<String, Object>();
            map.forEach((key, item) -> {
                if (!(key instanceof String text) || text.isBlank()) {
                    throw new IllegalArgumentException("selector keys must be non-blank strings");
                }
                copy.put(text, snapshot(item, depth + 1));
            });
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) {
            if (list.size() > 100) throw new IllegalArgumentException("selector list is too large");
            var copy = new ArrayList<Object>();
            list.forEach(item -> copy.add(snapshot(item, depth + 1)));
            return Collections.unmodifiableList(copy);
        }
        if (value instanceof String || value instanceof Boolean || value instanceof Number) return value;
        throw new IllegalArgumentException("selector values must be non-null JSON values");
    }
}
