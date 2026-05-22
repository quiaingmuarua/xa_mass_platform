package com.xa.mass.trace.operator;

import com.xa.mass.trace.scenario.TraceScenarioIssue;

import java.util.List;
import java.util.Map;

public record TraceAnalyzeResponse(
        String scenarioId,
        String taskId,
        String source,
        boolean ok,
        int eventCount,
        Map<String, Long> eventTypeCounts,
        List<TraceScenarioIssue> issues
) {
}
