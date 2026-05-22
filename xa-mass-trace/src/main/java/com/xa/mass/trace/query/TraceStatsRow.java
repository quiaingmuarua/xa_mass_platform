package com.xa.mass.trace.query;

public record TraceStatsRow(
        String eventType,
        String severity,
        long count
) {
}
