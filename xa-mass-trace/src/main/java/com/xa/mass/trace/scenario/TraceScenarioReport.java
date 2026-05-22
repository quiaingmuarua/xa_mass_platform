package com.xa.mass.trace.scenario;

import java.util.List;
import java.util.Map;

public record TraceScenarioReport(
        String scenarioId,
        String taskId,
        String source,
        boolean ok,
        int eventCount,
        Map<String, Long> eventTypeCounts,
        List<TraceScenarioIssue> issues
) {
}
