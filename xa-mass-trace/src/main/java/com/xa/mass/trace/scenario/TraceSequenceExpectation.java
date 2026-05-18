package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceTimelineRow;

public record TraceSequenceExpectation(
        String eventType,
        String src,
        String dst,
        String terminalReason
) {

    public static TraceSequenceExpectation event(String eventType) {
        return new TraceSequenceExpectation(eventType, null, null, null);
    }

    public TraceSequenceExpectation transition(String src, String dst) {
        return new TraceSequenceExpectation(eventType, src, dst, terminalReason);
    }

    public TraceSequenceExpectation terminalReason(String terminalReason) {
        return new TraceSequenceExpectation(eventType, src, dst, terminalReason);
    }

    boolean matches(TraceTimelineRow row) {
        if (row == null || !eventType.equals(row.eventType())) {
            return false;
        }
        if (src != null && !src.equals(row.src())) {
            return false;
        }
        if (dst != null && !dst.equals(row.dst())) {
            return false;
        }
        return terminalReason == null || terminalReason.equals(row.terminalReason());
    }

    String describe() {
        StringBuilder builder = new StringBuilder(eventType);
        if (src != null || dst != null) {
            builder.append('[')
                    .append(src == null ? "*" : src)
                    .append("->")
                    .append(dst == null ? "*" : dst)
                    .append(']');
        }
        if (terminalReason != null) {
            builder.append("{terminalReason=").append(terminalReason).append('}');
        }
        return builder.toString();
    }
}
