package com.xa.mass.trace.query;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;

public final class DuckDbTraceQueryBackend implements TraceQueryBackend {

    @Override
    public List<TraceTimelineRow> timeline(TraceSource source,
                                           String taskId,
                                           String messageId,
                                           int limit) throws Exception {
        String identityJson = "to_json(identity)";
        String transitionJson = "to_json(transition)";
        String outcomeJson = "to_json(outcome)";
        String where = "WHERE json_extract_string(%s, '$.taskId') = '%s'".formatted(identityJson, sql(taskId));
        if (messageId != null && !messageId.isBlank()) {
            where += " AND json_extract_string(%s, '$.messageId') = '%s'".formatted(identityJson, sql(messageId));
        }
        String query = """
                SELECT
                    ts,
                    tsIso,
                    eventType,
                    severity,
                    traceId,
                    json_extract_string(%s, '$.taskId') AS taskId,
                    json_extract_string(%s, '$.messageId') AS messageId,
                    json_extract_string(%s, '$.attemptId') AS attemptId,
                    json_extract_string(%s, '$.workerId') AS workerId,
                    json_extract_string(%s, '$.workerContextId') AS workerContextId,
                    json_extract_string(%s, '$.src') AS src,
                    json_extract_string(%s, '$.dst') AS dst,
                    json_extract_string(%s, '$.reason') AS transitionReason,
                    try_cast(json_extract(%s, '$.success') AS BOOLEAN) AS outcomeSuccess,
                    json_extract_string(%s, '$.errorCode') AS outcomeErrorCode,
                    json_extract_string(%s, '$.detail') AS outcomeDetail,
                    json_extract_string(to_json(attrs), '$.trigger') AS trigger,
                    json_extract_string(to_json(attrs), '$.source') AS source,
                    json_extract_string(to_json(attrs), '$.reason') AS reason,
                    json_extract_string(to_json(attrs), '$.terminalReason') AS terminalReason
                FROM read_ndjson('%s')
                %s
                ORDER BY ts, eventId
                LIMIT %d
                """.formatted(
                identityJson,
                identityJson,
                identityJson,
                identityJson,
                identityJson,
                transitionJson,
                transitionJson,
                transitionJson,
                outcomeJson,
                outcomeJson,
                outcomeJson,
                sql(source.duckDbPattern()),
                where,
                limit);
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            List<TraceTimelineRow> rows = new ArrayList<>();
            while (resultSet.next()) {
                Boolean outcomeSuccess = resultSet.getObject("outcomeSuccess") == null
                        ? null
                        : resultSet.getBoolean("outcomeSuccess");
                rows.add(new TraceTimelineRow(
                        resultSet.getLong("ts"),
                        resultSet.getString("tsIso"),
                        resultSet.getString("eventType"),
                        resultSet.getString("severity"),
                        resultSet.getString("traceId"),
                        resultSet.getString("taskId"),
                        resultSet.getString("messageId"),
                        resultSet.getString("attemptId"),
                        resultSet.getString("workerId"),
                        resultSet.getString("workerContextId"),
                        resultSet.getString("src"),
                        resultSet.getString("dst"),
                        resultSet.getString("transitionReason"),
                        outcomeSuccess,
                        resultSet.getString("outcomeErrorCode"),
                        resultSet.getString("outcomeDetail"),
                        resultSet.getString("trigger"),
                        resultSet.getString("source"),
                        resultSet.getString("reason"),
                        resultSet.getString("terminalReason")
                ));
            }
            return rows;
        }
    }

    @Override
    public List<TraceStatsRow> stats(TraceSource source,
                                     String taskId,
                                     String eventType,
                                     String severity,
                                     int limit) throws Exception {
        StringBuilder where = new StringBuilder("WHERE 1=1");
        if (taskId != null && !taskId.isBlank()) {
            where.append(" AND json_extract_string(to_json(identity), '$.taskId') = '")
                    .append(sql(taskId))
                    .append("'");
        }
        if (eventType != null && !eventType.isBlank()) {
            where.append(" AND eventType = '").append(sql(eventType)).append("'");
        }
        if (severity != null && !severity.isBlank()) {
            where.append(" AND severity = '").append(sql(severity)).append("'");
        }
        String query = """
                SELECT eventType, severity, count(*) AS cnt
                FROM read_ndjson('%s')
                %s
                GROUP BY eventType, severity
                ORDER BY cnt DESC, eventType, severity
                LIMIT %d
                """.formatted(sql(source.duckDbPattern()), where, limit);
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            List<TraceStatsRow> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(new TraceStatsRow(
                        resultSet.getString("eventType"),
                        resultSet.getString("severity"),
                        resultSet.getLong("cnt")
                ));
            }
            return rows;
        }
    }

    @Override
    public List<TraceAssignmentRow> assignment(TraceSource source,
                                               String taskId,
                                               int limit) throws Exception {
        String identityJson = "to_json(identity)";
        String transitionJson = "to_json(transition)";
        String attrsJson = "to_json(attrs)";
        String where = """
                WHERE json_extract_string(%s, '$.taskId') = '%s'
                  AND eventType IN (
                    'DISPATCH_REQUESTED',
                    'DISPATCH_SKIPPED',
                    'ASSIGNMENT_SUMMARY',
                    'DISPATCH_BINDING_SUMMARY',
                    'ASSIGNMENT_QUEUE_SNAPSHOT',
                    'ASSIGNMENT_RETRY_SCHEDULED',
                    'WORKER_MATCH_ACCEPTED',
                    'WORKER_MATCH_REJECTED',
                    'WORKER_LOCK_ACQUIRED',
                    'WORKER_LOCK_RELEASED',
                    'TASK_STATUS_TRANSITION',
                    'TASK_WORK_ATTEMPT_STATUS_TRANSITION',
                    'WORKER_CONTEXT_STATUS_TRANSITION',
                    'RESOURCE_RELEASED',
                    'RESOURCE_RELEASE_FAILED',
                    'LEASE_EXPIRED'
                  )
                """.formatted(identityJson, sql(taskId));
        String query = """
                SELECT
                    ts,
                    tsIso,
                    eventType,
                    severity,
                    json_extract_string(%s, '$.taskId') AS taskId,
                    json_extract_string(%s, '$.messageId') AS messageId,
                    json_extract_string(%s, '$.attemptId') AS attemptId,
                    json_extract_string(%s, '$.workerId') AS workerId,
                    json_extract_string(%s, '$.workerContextId') AS workerContextId,
                    json_extract_string(%s, '$.workerSchedulingResourceId') AS workerSchedulingResourceId,
                    json_extract_string(%s, '$.workerSchedulingRoutingTags') AS workerSchedulingRoutingTags,
                    json_extract_string(%s, '$.workerSchedulingAttributes') AS workerSchedulingAttributes,
                    try_cast(json_extract_string(%s, '$.workerSchedulingMatchesRoutingCode') AS BOOLEAN) AS workerSchedulingMatchesRoutingCode,
                    try_cast(json_extract_string(%s, '$.candidateRank') AS INTEGER) AS candidateRank,
                    try_cast(json_extract_string(%s, '$.candidateScore') AS DOUBLE) AS candidateScore,
                    try_cast(json_extract_string(%s, '$.workerActiveLeaseCount') AS INTEGER) AS workerActiveLeaseCount,
                    try_cast(json_extract_string(%s, '$.workerReservedCount') AS INTEGER) AS workerReservedCount,
                    try_cast(json_extract_string(%s, '$.workerDeclaredCapacity') AS INTEGER) AS workerDeclaredCapacity,
                    try_cast(json_extract_string(%s, '$.workerEstimatedLoadRatio') AS DOUBLE) AS workerEstimatedLoadRatio,
                    json_extract_string(%s, '$.trigger') AS trigger,
                    json_extract_string(%s, '$.source') AS source,
                    json_extract_string(%s, '$.reason') AS reason,
                    json_extract_string(%s, '$.result') AS result,
                    json_extract_string(%s, '$.initialStatus') AS initialStatus,
                    coalesce(
                        json_extract_string(%s, '$.currentStatus'),
                        json_extract_string(%s, '$.taskStatus')
                    ) AS currentStatus,
                    json_extract_string(%s, '$.dispatchLane') AS dispatchLane,
                    json_extract_string(%s, '$.dispatchPriority') AS dispatchPriority,
                    json_extract_string(%s, '$.workloadClass') AS workloadClass,
                    try_cast(json_extract_string(%s, '$.foreground') AS BOOLEAN) AS foreground,
                    json_extract_string(%s, '$.batchPolicy') AS batchPolicy,
                    json_extract_string(%s, '$.leaseProfile') AS leaseProfile,
                    try_cast(json_extract_string(%s, '$.pendingDispatchCount') AS INTEGER) AS pendingDispatchCount,
                    try_cast(json_extract_string(%s, '$.desiredDispatchWorkerCount') AS INTEGER) AS desiredDispatchWorkerCount,
                    try_cast(json_extract_string(%s, '$.requiredStartWorkerCount') AS INTEGER) AS requiredStartWorkerCount,
                    try_cast(json_extract_string(%s, '$.requestedMatchCount') AS INTEGER) AS requestedMatchCount,
                    try_cast(json_extract_string(%s, '$.workerBudget') AS INTEGER) AS workerBudget,
                    try_cast(json_extract_string(%s, '$.currentTaskWorkerCount') AS INTEGER) AS currentTaskWorkerCount,
                    try_cast(json_extract_string(%s, '$.budgetLimited') AS BOOLEAN) AS budgetLimited,
                    try_cast(json_extract_string(%s, '$.matchedWorkerCount') AS INTEGER) AS matchedWorkerCount,
                    try_cast(json_extract_string(%s, '$.dispatchCandidateCount') AS INTEGER) AS dispatchCandidateCount,
                    try_cast(json_extract_string(%s, '$.dispatchedMessageCount') AS INTEGER) AS dispatchedMessageCount,
                    try_cast(json_extract_string(%s, '$.usedWorkerCount') AS INTEGER) AS usedWorkerCount,
                    try_cast(json_extract_string(%s, '$.peakAssignedWorkerCount') AS INTEGER) AS peakAssignedWorkerCount,
                    try_cast(json_extract_string(%s, '$.pendingMessageCount') AS INTEGER) AS pendingMessageCount,
                    try_cast(json_extract_string(%s, '$.dispatchSlotCount') AS INTEGER) AS dispatchSlotCount,
                    try_cast(json_extract_string(%s, '$.unassignedMessageCount') AS INTEGER) AS unassignedMessageCount,
                    try_cast(json_extract_string(%s, '$.uniqueWorkerCount') AS INTEGER) AS uniqueWorkerCount,
                    try_cast(json_extract_string(%s, '$.uniqueWorkerContextCount') AS INTEGER) AS uniqueWorkerContextCount,
                    try_cast(json_extract_string(%s, '$.perWorkerBatchLimit') AS INTEGER) AS perWorkerBatchLimit,
                    try_cast(json_extract_string(%s, '$.queueDepth') AS INTEGER) AS queueDepth,
                    try_cast(json_extract_string(%s, '$.trackedBatchPendingCount') AS INTEGER) AS trackedBatchPendingCount,
                    try_cast(json_extract_string(%s, '$.scheduledRetryCount') AS INTEGER) AS scheduledRetryCount,
                    json_extract_string(%s, '$.queueAction') AS queueAction,
                    try_cast(json_extract_string(%s, '$.retryDelayMillis') AS BIGINT) AS retryDelayMillis,
                    json_extract_string(%s, '$.src') AS src,
                    json_extract_string(%s, '$.dst') AS dst
                FROM read_ndjson('%s')
                %s
                ORDER BY ts, eventId
                LIMIT %d
                """.formatted(
                identityJson, identityJson, identityJson, identityJson, identityJson,
                attrsJson, attrsJson, attrsJson, attrsJson,
                attrsJson, attrsJson, attrsJson, attrsJson, attrsJson, attrsJson,
                attrsJson, attrsJson, attrsJson, attrsJson, attrsJson, attrsJson, attrsJson,
                attrsJson, attrsJson, attrsJson, attrsJson, attrsJson, attrsJson,
                attrsJson, attrsJson,
                attrsJson, attrsJson, attrsJson, attrsJson, attrsJson, attrsJson,
                attrsJson, attrsJson, attrsJson, attrsJson, attrsJson, attrsJson,
                attrsJson, attrsJson, attrsJson,
                attrsJson, attrsJson, attrsJson, attrsJson, attrsJson, attrsJson,
                transitionJson, transitionJson,
                sql(source.duckDbPattern()),
                where,
                limit);
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            List<TraceAssignmentRow> rows = new ArrayList<>();
            while (resultSet.next()) {
                rows.add(new TraceAssignmentRow(
                        resultSet.getLong("ts"),
                        resultSet.getString("tsIso"),
                        resultSet.getString("eventType"),
                        resultSet.getString("severity"),
                        resultSet.getString("taskId"),
                        resultSet.getString("messageId"),
                        resultSet.getString("attemptId"),
                        resultSet.getString("workerId"),
                        resultSet.getString("workerContextId"),
                        resultSet.getString("workerSchedulingResourceId"),
                        resultSet.getString("workerSchedulingRoutingTags"),
                        resultSet.getString("workerSchedulingAttributes"),
                        booleanOrNull(resultSet, "workerSchedulingMatchesRoutingCode"),
                        integerOrNull(resultSet, "candidateRank"),
                        doubleOrNull(resultSet, "candidateScore"),
                        integerOrNull(resultSet, "workerActiveLeaseCount"),
                        integerOrNull(resultSet, "workerReservedCount"),
                        integerOrNull(resultSet, "workerDeclaredCapacity"),
                        doubleOrNull(resultSet, "workerEstimatedLoadRatio"),
                        resultSet.getString("trigger"),
                        resultSet.getString("source"),
                        resultSet.getString("reason"),
                        resultSet.getString("result"),
                        resultSet.getString("initialStatus"),
                        resultSet.getString("currentStatus"),
                        resultSet.getString("dispatchLane"),
                        resultSet.getString("dispatchPriority"),
                        resultSet.getString("workloadClass"),
                        booleanOrNull(resultSet, "foreground"),
                        resultSet.getString("batchPolicy"),
                        resultSet.getString("leaseProfile"),
                        integerOrNull(resultSet, "pendingDispatchCount"),
                        integerOrNull(resultSet, "desiredDispatchWorkerCount"),
                        integerOrNull(resultSet, "requiredStartWorkerCount"),
                        integerOrNull(resultSet, "requestedMatchCount"),
                        integerOrNull(resultSet, "workerBudget"),
                        integerOrNull(resultSet, "currentTaskWorkerCount"),
                        booleanOrNull(resultSet, "budgetLimited"),
                        integerOrNull(resultSet, "matchedWorkerCount"),
                        integerOrNull(resultSet, "dispatchCandidateCount"),
                        integerOrNull(resultSet, "dispatchedMessageCount"),
                        integerOrNull(resultSet, "usedWorkerCount"),
                        integerOrNull(resultSet, "peakAssignedWorkerCount"),
                        integerOrNull(resultSet, "pendingMessageCount"),
                        integerOrNull(resultSet, "dispatchSlotCount"),
                        integerOrNull(resultSet, "unassignedMessageCount"),
                        integerOrNull(resultSet, "uniqueWorkerCount"),
                        integerOrNull(resultSet, "uniqueWorkerContextCount"),
                        integerOrNull(resultSet, "perWorkerBatchLimit"),
                        integerOrNull(resultSet, "queueDepth"),
                        integerOrNull(resultSet, "trackedBatchPendingCount"),
                        integerOrNull(resultSet, "scheduledRetryCount"),
                        resultSet.getString("queueAction"),
                        longOrNull(resultSet, "retryDelayMillis"),
                        resultSet.getString("src"),
                        resultSet.getString("dst")
                ));
            }
            return rows;
        }
    }

    @Override
    public long countRows(TraceSource source) throws Exception {
        String query = "SELECT count(*) AS cnt FROM read_ndjson('%s')".formatted(sql(source.duckDbPattern()));
        try (Connection connection = DriverManager.getConnection("jdbc:duckdb:");
             Statement statement = connection.createStatement();
             ResultSet resultSet = statement.executeQuery(query)) {
            if (!resultSet.next()) {
                return 0L;
            }
            return resultSet.getLong("cnt");
        }
    }

    private static String sql(String value) {
        return value.replace("'", "''");
    }

    private static Integer integerOrNull(ResultSet resultSet, String column) throws Exception {
        int value = resultSet.getInt(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Long longOrNull(ResultSet resultSet, String column) throws Exception {
        long value = resultSet.getLong(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Boolean booleanOrNull(ResultSet resultSet, String column) throws Exception {
        boolean value = resultSet.getBoolean(column);
        return resultSet.wasNull() ? null : value;
    }

    private static Double doubleOrNull(ResultSet resultSet, String column) throws Exception {
        double value = resultSet.getDouble(column);
        return resultSet.wasNull() ? null : value;
    }
}
