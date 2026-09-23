package com.xa.mass.scenario.appchecks;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.List;
import java.util.Map;

/** Statistics of observed allocations; neither an execution counter nor a quota. */
final class AppCheckAssignmentWindow {
    private static final String LAST = "lastAssignedAt";
    private static final String COUNT = "windowAssignmentCount";
    private final long windowMillis;

    AppCheckAssignmentWindow(long windowMillis) {
        if (windowMillis <= 0) throw new IllegalArgumentException("windowMillis must be positive");
        this.windowMillis = windowMillis;
    }

    Map<String, Object> project(Map<String, Object> properties, List<Long> observations) {
        boolean present = properties.containsKey(LAST);
        if (present != properties.containsKey(COUNT)) throw new IllegalArgumentException("Incomplete assignment window");
        long last = present ? integer(properties.get(LAST)) : 0;
        long count = present ? integer(properties.get(COUNT)) : 0;
        long window = present ? last / windowMillis : -1;
        boolean changed = false;
        for (long observedAt : observations) {
            if (observedAt < 0) throw new IllegalArgumentException("Negative observation time");
            long observedWindow = observedAt / windowMillis;
            if (observedWindow < window) continue;
            if (observedWindow > window) {
                window = observedWindow;
                count = 0;
                last = observedAt;
            }
            count = Math.addExact(count, 1);
            last = Math.max(last, observedAt);
            changed = true;
        }
        return changed ? Map.of(LAST, last, COUNT, count) : Map.of();
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
