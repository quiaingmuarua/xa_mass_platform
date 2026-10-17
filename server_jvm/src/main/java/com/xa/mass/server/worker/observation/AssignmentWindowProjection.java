package com.xa.mass.server.worker.observation;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Pure observation arithmetic; neither an execution counter nor a reserved quota. */
final class AssignmentWindowProjection {
    private final long windowMillis;
    private final String timestampProperty;
    private final String countProperty;

    AssignmentWindowProjection(long windowMillis, String timestampProperty, String countProperty) {
        if (windowMillis <= 0 || timestampProperty == null || timestampProperty.isBlank()
                || countProperty == null || countProperty.isBlank() || timestampProperty.equals(countProperty))
            throw new IllegalArgumentException("Invalid assignment window parameters");
        this.windowMillis = windowMillis;
        this.timestampProperty = timestampProperty;
        this.countProperty = countProperty;
    }

    Map<String, Object> project(Map<String, Object> properties, List<Long> observations) {
        boolean present = properties.containsKey(timestampProperty);
        if (present != properties.containsKey(countProperty)) throw new IllegalArgumentException("Incomplete assignment window");
        long last = present ? integer(properties.get(timestampProperty)) : 0;
        long count = present ? integer(properties.get(countProperty)) : 0;
        long window = present ? last / windowMillis : -1;
        boolean changed = false;
        for (long observedAt : observations) {
            if (observedAt < 0) throw new IllegalArgumentException("Negative observation time");
            long observedWindow = observedAt / windowMillis;
            if (observedWindow < window) continue;
            if (observedWindow > window) { window = observedWindow; count = 0; last = observedAt; }
            count = Math.addExact(count, 1);
            last = Math.max(last, observedAt);
            changed = true;
        }
        return changed ? Map.of(timestampProperty, last, countProperty, count) : Map.of();
    }

    private static long integer(Object value) {
        long result = switch (value) {
            case Byte number -> number.longValue();
            case Short number -> number.longValue();
            case Integer number -> number.longValue();
            case Long number -> number;
            case BigInteger number -> number.longValueExact();
            case BigDecimal number -> number.longValueExact();
            default -> throw new IllegalArgumentException("Assignment window fields must be integers");
        };
        if (result < 0) throw new IllegalArgumentException("Assignment window fields must be non-negative");
        return result;
    }
}
