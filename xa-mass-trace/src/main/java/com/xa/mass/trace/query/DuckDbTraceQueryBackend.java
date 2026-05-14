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
}
