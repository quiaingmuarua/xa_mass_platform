package com.xa.mass.kernel.task;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/** One immutable binding-to-parameters Map. Property semantics belong to Matching. */
public record TaskItemWorkerSelector(Map<String, List<String>> expression) {

    private static final int MAX_TARGET_WORKERS = 100;
    private static final String WORKER_ID = "workerId";

    public TaskItemWorkerSelector {
        if (expression == null || expression.size() > 1) {
            throw new IllegalArgumentException("workerSelector must be an empty object or one binding/parameters entry");
        }
        if (expression.isEmpty()) {
            expression = Map.of();
        } else {
            Map.Entry<?, ?> entry = expression.entrySet().iterator().next();
            if (!(entry.getKey() instanceof String binding) || binding.isBlank()
                    || !(entry.getValue() instanceof List<?> values)
                    || values.isEmpty() || values.size() > MAX_TARGET_WORKERS) {
                throw new IllegalArgumentException("selector requires a non-blank binding and 1..100 string parameters");
            }
            var parameters = new ArrayList<String>(values.size());
            for (Object value : values) {
                if (!(value instanceof String parameter)) {
                    throw new IllegalArgumentException("selector parameters must be non-null strings");
                }
                parameters.add(parameter);
            }
            if (WORKER_ID.equals(binding) && (parameters.stream().anyMatch(String::isBlank)
                    || new HashSet<>(parameters).size() != parameters.size())) {
                throw new IllegalArgumentException("workerId selector requires unique non-blank identities");
            }
            expression = Map.of(binding, List.copyOf(parameters));
        }
    }

    @SuppressWarnings("unchecked")
    public static TaskItemWorkerSelector parse(Map<?, ?> expression) {
        // The constructor validates every raw HTTP/storage entry before immutable capture.
        return new TaskItemWorkerSelector((Map<String, List<String>>) expression);
    }

    public boolean isAny() {
        return expression.isEmpty();
    }

    public boolean hasExplicitWorkerIds() {
        return expression.containsKey(WORKER_ID);
    }

    public List<String> targetWorkerIds() {
        if (!hasExplicitWorkerIds()) {
            throw new IllegalStateException("selector does not contain explicit Worker identities");
        }
        return expression.get(WORKER_ID);
    }
}
