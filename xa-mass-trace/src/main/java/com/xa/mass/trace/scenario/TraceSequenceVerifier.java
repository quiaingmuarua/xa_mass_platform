package com.xa.mass.trace.scenario;

import com.xa.mass.trace.query.TraceTimelineRow;

import java.util.List;

public final class TraceSequenceVerifier {

    private TraceSequenceVerifier() {
    }

    public static void requireOrdered(List<TraceTimelineRow> rows,
                                      List<TraceScenarioIssue> issues,
                                      String issueCode,
                                      String description,
                                      TraceSequenceExpectation... expectations) {
        long minTimestamp = Long.MIN_VALUE;
        for (TraceSequenceExpectation expectation : expectations) {
            TraceTimelineRow matched = findAtOrAfter(rows, expectation, minTimestamp);
            if (matched == null) {
                issues.add(new TraceScenarioIssue(
                        issueCode,
                        description + " missing ordered event " + expectation.describe()
                                + " at or after timestamp " + minTimestamp));
                return;
            }
            minTimestamp = matched.ts();
        }
    }

    private static TraceTimelineRow findAtOrAfter(List<TraceTimelineRow> rows,
                                                  TraceSequenceExpectation expectation,
                                                  long minTimestamp) {
        TraceTimelineRow best = null;
        for (TraceTimelineRow row : rows) {
            if (row.ts() < minTimestamp || !expectation.matches(row)) {
                continue;
            }
            if (best == null || row.ts() < best.ts()) {
                best = row;
            }
        }
        return best;
    }
}
