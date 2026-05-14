package com.xa.mass.trace.query;

import java.util.List;

public record TraceValidationReport(
        PathSummary source,
        boolean valid,
        long validRows,
        List<TraceValidationIssue> issues
) {
    public record PathSummary(
            String inputPath,
            int fileCount
    ) {
    }
}
