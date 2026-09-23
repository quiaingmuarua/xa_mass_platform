package com.xa.mass.kernel.assignment;

import com.fasterxml.jackson.annotation.JsonCreator;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable shared Pool waterline declaration, never a Task-private reservation. */
public record RefillTarget(String poolName, EligibilityQuery target, int count) {
    public RefillTarget {
        if (poolName == null || poolName.isBlank()) throw new IllegalArgumentException("poolName must be non-blank");
        Objects.requireNonNull(target, "target");
        if (count < 1 || count > 1000) throw new IllegalArgumentException("count requires 1..1000");
    }
    public static RefillTarget of(String poolName, EligibilityQuery target, int count) {
        return new RefillTarget(poolName, target, count);
    }
    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RefillTarget parse(Map<String, Object> value) {
        if (value == null || !value.keySet().equals(Set.of("poolName", "target", "count"))
                || !(value.get("poolName") instanceof String pool)
                || !(value.get("target") instanceof Map<?, ?> target)
                || !(value.get("count") instanceof Integer || value.get("count") instanceof Long)) {
            throw new IllegalArgumentException("refill requires poolName, target and integer count");
        }
        long count = ((Number) value.get("count")).longValue();
        if (count < 1 || count > 1000) throw new IllegalArgumentException("count requires 1..1000");
        return new RefillTarget(pool, EligibilityQuery.parse(target), (int) count);
    }
}
