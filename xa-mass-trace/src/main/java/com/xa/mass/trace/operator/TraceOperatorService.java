package com.xa.mass.trace.operator;

import com.xa.mass.trace.query.DuckDbTraceQueryBackend;
import com.xa.mass.trace.query.TraceQueryBackend;
import com.xa.mass.trace.query.TraceQueryFilter;
import com.xa.mass.trace.query.TraceSource;
import com.xa.mass.trace.query.TraceSourceResolver;
import com.xa.mass.trace.query.TraceValidationReport;
import com.xa.mass.trace.query.TraceValidationService;
import com.xa.mass.trace.scenario.TraceScenarioRegistry;
import com.xa.mass.trace.scenario.TraceScenarioReport;

import java.util.List;

public final class TraceOperatorService {

    private static final int DEFAULT_TIMELINE_LIMIT = 500;
    private static final int DEFAULT_STATS_LIMIT = 100;

    private final TraceQueryBackend queryBackend;
    private final TraceValidationService validationService;
    private final TraceScenarioRegistry scenarioRegistry;

    public TraceOperatorService() {
        this(new DuckDbTraceQueryBackend());
    }

    public TraceOperatorService(TraceQueryBackend queryBackend) {
        this(queryBackend, new TraceValidationService(queryBackend), new TraceScenarioRegistry());
    }

    TraceOperatorService(TraceQueryBackend queryBackend,
                         TraceValidationService validationService,
                         TraceScenarioRegistry scenarioRegistry) {
        this.queryBackend = queryBackend;
        this.validationService = validationService;
        this.scenarioRegistry = scenarioRegistry;
    }

    public TraceQueryResponse query(TraceQueryRequest request) throws Exception {
        TraceSource source = TraceSourceResolver.resolve(request.path());
        int limit = positiveOrDefault(request.limit(), DEFAULT_TIMELINE_LIMIT, "limit");
        TraceQueryFilter filter = new TraceQueryFilter(
                blankToNull(request.taskId()),
                blankToNull(request.messageId()),
                blankToNull(request.workerId()),
                blankToNull(request.commandId()),
                blankToNull(request.traceId()),
                blankToNull(request.eventType())
        );
        if (!filter.hasAnyFilter()) {
            throw new IllegalArgumentException(
                    "At least one query filter is required: taskId, messageId, workerId, commandId, traceId, or eventType");
        }
        var rows = queryBackend.query(source, filter, limit);
        return new TraceQueryResponse(
                source.inputPath().toString(),
                filter.taskId(),
                filter.messageId(),
                filter.workerId(),
                filter.commandId(),
                filter.traceId(),
                filter.eventType(),
                rows.size(),
                List.copyOf(rows)
        );
    }

    public TraceTimelineResponse timeline(TraceTimelineRequest request) throws Exception {
        TraceSource source = TraceSourceResolver.resolve(request.path());
        String taskId = requireText(request.taskId(), "taskId");
        int limit = positiveOrDefault(request.limit(), DEFAULT_TIMELINE_LIMIT, "limit");
        var rows = queryBackend.timeline(source, taskId, blankToNull(request.messageId()), limit);
        return new TraceTimelineResponse(
                source.inputPath().toString(),
                taskId,
                blankToNull(request.messageId()),
                rows.size(),
                List.copyOf(rows)
        );
    }

    public TraceStatsResponse stats(TraceStatsRequest request) throws Exception {
        TraceSource source = TraceSourceResolver.resolve(request.path());
        int limit = positiveOrDefault(request.limit(), DEFAULT_STATS_LIMIT, "limit");
        var rows = queryBackend.stats(
                source,
                blankToNull(request.taskId()),
                blankToNull(request.eventType()),
                blankToNull(request.severity()),
                limit
        );
        return new TraceStatsResponse(
                source.inputPath().toString(),
                blankToNull(request.taskId()),
                blankToNull(request.eventType()),
                blankToNull(request.severity()),
                rows.size(),
                List.copyOf(rows)
        );
    }

    public TraceAssignmentResponse assignment(TraceAssignmentRequest request) throws Exception {
        TraceSource source = TraceSourceResolver.resolve(request.path());
        String taskId = requireText(request.taskId(), "taskId");
        int limit = positiveOrDefault(request.limit(), DEFAULT_TIMELINE_LIMIT, "limit");
        var rows = queryBackend.assignment(source, taskId, limit);
        return new TraceAssignmentResponse(
                source.inputPath().toString(),
                taskId,
                rows.size(),
                List.copyOf(rows)
        );
    }

    public TraceValidateResponse validate(TraceValidateRequest request) throws Exception {
        TraceSource source = TraceSourceResolver.resolve(request.path());
        TraceValidationReport report = validationService.validate(source);
        return new TraceValidateResponse(
                new TraceValidateResponse.PathSummary(
                        report.source().inputPath(),
                        report.source().fileCount()
                ),
                report.valid(),
                report.validRows(),
                List.copyOf(report.issues())
        );
    }

    public TraceAnalyzeResponse analyze(TraceAnalyzeRequest request) throws Exception {
        TraceSource source = TraceSourceResolver.resolve(request.path());
        String scenarioId = requireText(request.scenarioId(), "scenarioId");
        String taskId = requireText(request.taskId(), "taskId");
        TraceScenarioReport report = scenarioRegistry.require(scenarioId)
                .analyze(queryBackend, source, taskId);
        return new TraceAnalyzeResponse(
                report.scenarioId(),
                report.taskId(),
                report.source(),
                report.ok(),
                report.eventCount(),
                report.eventTypeCounts(),
                report.issues()
        );
    }

    public List<String> scenarioIds() {
        return scenarioRegistry.ids();
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing required field: " + field);
        }
        return value;
    }

    private static int positiveOrDefault(Integer value, int defaultValue, String field) {
        if (value == null) {
            return defaultValue;
        }
        if (value <= 0) {
            throw new IllegalArgumentException(field + " must be > 0");
        }
        return value;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value;
    }
}
