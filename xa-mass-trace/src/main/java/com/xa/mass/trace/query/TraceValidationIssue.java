package com.xa.mass.trace.query;

public record TraceValidationIssue(
        String code,
        String file,
        int line,
        String message
) {
}
