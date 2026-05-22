package com.xa.mass.trace.operator;

import com.xa.mass.trace.query.TraceValidationIssue;

import java.util.List;

public record TraceValidateResponse(
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
