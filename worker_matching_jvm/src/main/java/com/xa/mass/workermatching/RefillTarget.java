package com.xa.mass.workermatching;

import com.fasterxml.jackson.annotation.JsonCreator;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.xa.mass.kernel.assignment.EligibilityQuery;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** A declared stock target; quantities are not part of query identity. */
public final class RefillTarget {
    private final EligibilityQuery query;
    private final int count;

    /** Standard configuration constructor keeps YAML query/count flat and requires no converter. */
    public RefillTarget(Map<String, List<String>> query, int count) {
        this.query = new EligibilityQuery(query == null ? Map.of() : query);
        if (count < 1 || count > 1000) throw new IllegalArgumentException("target count requires 1..1000");
        this.count = count;
    }

    public static RefillTarget of(EligibilityQuery query, int count) {
        return new RefillTarget(Objects.requireNonNull(query).query(), count);
    }

    @JsonCreator(mode = JsonCreator.Mode.DELEGATING)
    public static RefillTarget parse(Map<String, Object> target) {
        if (target == null || !Set.of("query", "count").containsAll(target.keySet())
                || !(target.get("count") instanceof Integer || target.get("count") instanceof Long)) {
            throw new IllegalArgumentException("refill target requires query and integer count only");
        }
        Object raw = target.get("query");
        if (raw != null && !(raw instanceof Map<?, ?>)) {
            throw new IllegalArgumentException("refill query requires an object");
        }
        long count = ((Number) target.get("count")).longValue();
        if (count < 1 || count > 1000) throw new IllegalArgumentException("target count requires 1..1000");
        return of(EligibilityQuery.parse(raw == null ? Map.of() : (Map<?, ?>) raw), (int) count);
    }

    @JsonProperty("query")
    public EligibilityQuery query() { return query; }

    @JsonProperty("count")
    public int count() { return count; }

    @Override public boolean equals(Object other) {
        return other instanceof RefillTarget target && count == target.count && query.equals(target.query);
    }
    @Override public int hashCode() { return Objects.hash(query, count); }
    @Override public String toString() { return "RefillTarget[query=" + query + ", count=" + count + "]"; }
}
