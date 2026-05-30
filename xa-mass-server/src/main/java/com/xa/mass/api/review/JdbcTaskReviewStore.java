package com.xa.mass.api.review;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewAttempt;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewItem;
import com.xa.mass.api.review.TaskReviewReadModel.TaskReviewStats;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * JDBC server review/export materialization store.
 */
public final class JdbcTaskReviewStore implements TaskReviewStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<Map<String, Object>> MAP_TYPE = new TypeReference<>() { };

    private final DataSource dataSource;

    public JdbcTaskReviewStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        initializeSchema();
    }

    @Override
    public synchronized boolean upsertItem(String taskId, TaskReviewItem item) {
        if (isBlank(taskId) || item == null || isBlank(item.messageId())) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("""
                    DELETE FROM xa_task_review_item WHERE task_id = ? AND message_id = ?
                    """);
                 PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO xa_task_review_item(
                      task_id, message_id, event_code, status, final_reason, payload_ref,
                      retry_count, max_retry_count, create_time, assigned_time, start_time,
                      complete_time, update_time, input_json, worker_id, batch_id, attempt_id,
                      error_code, error_message, output_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                delete.setString(1, taskId);
                delete.setString(2, item.messageId());
                delete.executeUpdate();
                bindItem(insert, taskId, item);
                insert.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upsert task review item " + taskId + "/" + item.messageId(), e);
        }
    }

    @Override
    public Optional<TaskReviewItem> findItem(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return Optional.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT * FROM xa_task_review_item WHERE task_id = ? AND message_id = ?
                     """)) {
            ps.setString(1, taskId);
            ps.setString(2, messageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? Optional.of(readItem(rs)) : Optional.empty();
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to find task review item " + taskId + "/" + messageId, e);
        }
    }

    @Override
    public List<TaskReviewItem> listItems(String taskId, int limit) {
        if (isBlank(taskId) || limit <= 0) {
            return List.of();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT * FROM xa_task_review_item
                     WHERE task_id = ?
                     ORDER BY create_time NULLS LAST, message_id
                     LIMIT ?
                     """)) {
            ps.setString(1, taskId);
            ps.setInt(2, limit);
            List<TaskReviewItem> items = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    items.add(readItem(rs));
                }
            }
            return items;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list task review items " + taskId, e);
        }
    }

    @Override
    public synchronized boolean upsertAttempt(String taskId, String messageId, TaskReviewAttempt attempt) {
        if (isBlank(taskId) || isBlank(messageId) || attempt == null || isBlank(attempt.attemptId())) {
            return false;
        }
        try (Connection conn = dataSource.getConnection()) {
            boolean autoCommit = conn.getAutoCommit();
            conn.setAutoCommit(false);
            try (PreparedStatement delete = conn.prepareStatement("""
                    DELETE FROM xa_task_review_attempt WHERE task_id = ? AND message_id = ? AND attempt_id = ?
                    """);
                 PreparedStatement insert = conn.prepareStatement("""
                    INSERT INTO xa_task_review_attempt(
                      task_id, message_id, attempt_id, attempt_no, worker_id, batch_id, status,
                      final_reason, error_code, error_message, output_json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """)) {
                delete.setString(1, taskId);
                delete.setString(2, messageId);
                delete.setString(3, attempt.attemptId());
                delete.executeUpdate();
                bindAttempt(insert, taskId, messageId, attempt);
                insert.executeUpdate();
                conn.commit();
            } catch (Exception e) {
                conn.rollback();
                throw e;
            } finally {
                conn.setAutoCommit(autoCommit);
            }
            return true;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to upsert task review attempt " + taskId + "/" + messageId, e);
        }
    }

    @Override
    public List<TaskReviewAttempt> listAttempts(String taskId, String messageId) {
        if (isBlank(taskId) || isBlank(messageId)) {
            return List.of();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT * FROM xa_task_review_attempt
                     WHERE task_id = ? AND message_id = ?
                     ORDER BY attempt_no, attempt_id
                     """)) {
            ps.setString(1, taskId);
            ps.setString(2, messageId);
            List<TaskReviewAttempt> attempts = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    attempts.add(readAttempt(rs));
                }
            }
            return attempts;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list task review attempts " + taskId + "/" + messageId, e);
        }
    }

    @Override
    public TaskReviewStats stats(String taskId) {
        if (isBlank(taskId)) {
            return TaskReviewStats.empty();
        }
        try (Connection conn = dataSource.getConnection();
             PreparedStatement ps = conn.prepareStatement("""
                     SELECT status, COUNT(*) FROM xa_task_review_item
                     WHERE task_id = ?
                     GROUP BY status
                     """)) {
            ps.setString(1, taskId);
            long total = 0L;
            long success = 0L;
            long failed = 0L;
            long expired = 0L;
            long processing = 0L;
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    String status = normalize(rs.getString(1));
                    long count = rs.getLong(2);
                    total += count;
                    if ("SUCCESS".equals(status)) {
                        success += count;
                    } else if ("FAILED".equals(status)) {
                        failed += count;
                    } else if ("EXPIRED".equals(status)) {
                        expired += count;
                    } else if ("ASSIGNED".equals(status) || "RUNNING".equals(status)) {
                        processing += count;
                    }
                }
            }
            return new TaskReviewStats(total, success, failed, expired, processing);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load task review stats " + taskId, e);
        }
    }

    private void initializeSchema() {
        try (Connection conn = dataSource.getConnection();
             PreparedStatement items = conn.prepareStatement("""
                     CREATE TABLE IF NOT EXISTS xa_task_review_item (
                       task_id VARCHAR(128) NOT NULL,
                       message_id VARCHAR(128) NOT NULL,
                       event_code VARCHAR(256),
                       status VARCHAR(64),
                       final_reason VARCHAR(128),
                       payload_ref VARCHAR(512),
                       retry_count INT NOT NULL,
                       max_retry_count INT NOT NULL,
                       create_time TIMESTAMP,
                       assigned_time TIMESTAMP,
                       start_time TIMESTAMP,
                       complete_time TIMESTAMP,
                       update_time TIMESTAMP,
                       input_json TEXT,
                       worker_id VARCHAR(128),
                       batch_id VARCHAR(128),
                       attempt_id VARCHAR(128),
                       error_code VARCHAR(128),
                       error_message TEXT,
                       output_json TEXT,
                       PRIMARY KEY(task_id, message_id)
                     )
                     """);
             PreparedStatement attempts = conn.prepareStatement("""
                     CREATE TABLE IF NOT EXISTS xa_task_review_attempt (
                       task_id VARCHAR(128) NOT NULL,
                       message_id VARCHAR(128) NOT NULL,
                       attempt_id VARCHAR(128) NOT NULL,
                       attempt_no INT NOT NULL,
                       worker_id VARCHAR(128),
                       batch_id VARCHAR(128),
                       status VARCHAR(64),
                       final_reason VARCHAR(128),
                       error_code VARCHAR(128),
                       error_message TEXT,
                       output_json TEXT,
                       PRIMARY KEY(task_id, message_id, attempt_id)
                     )
                     """);
             PreparedStatement itemIndex = conn.prepareStatement("""
                     CREATE INDEX IF NOT EXISTS idx_xa_task_review_item_task
                     ON xa_task_review_item(task_id, create_time)
                     """);
             PreparedStatement attemptIndex = conn.prepareStatement("""
                     CREATE INDEX IF NOT EXISTS idx_xa_task_review_attempt_message
                     ON xa_task_review_attempt(task_id, message_id, attempt_no)
                     """)) {
            items.executeUpdate();
            attempts.executeUpdate();
            itemIndex.executeUpdate();
            attemptIndex.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to initialize task review JDBC schema", e);
        }
    }

    private static void bindItem(PreparedStatement ps, String taskId, TaskReviewItem item) throws Exception {
        ps.setString(1, taskId);
        ps.setString(2, item.messageId());
        ps.setString(3, item.eventCode());
        ps.setString(4, normalize(item.status()));
        ps.setString(5, item.finalReason());
        ps.setString(6, item.payloadRef());
        ps.setInt(7, item.retryCount());
        ps.setInt(8, item.maxRetryCount());
        setTimestamp(ps, 9, item.createTime());
        setTimestamp(ps, 10, item.assignedTime());
        setTimestamp(ps, 11, item.startTime());
        setTimestamp(ps, 12, item.completeTime());
        setTimestamp(ps, 13, item.updateTime());
        ps.setString(14, json(item.input()));
        ps.setString(15, item.workerId());
        ps.setString(16, item.batchId());
        ps.setString(17, item.attemptId());
        ps.setString(18, item.errorCode());
        ps.setString(19, item.errorMessage());
        ps.setString(20, json(item.output()));
    }

    private static void bindAttempt(PreparedStatement ps,
                                    String taskId,
                                    String messageId,
                                    TaskReviewAttempt attempt) throws Exception {
        ps.setString(1, taskId);
        ps.setString(2, messageId);
        ps.setString(3, attempt.attemptId());
        ps.setInt(4, attempt.attemptNo());
        ps.setString(5, attempt.workerId());
        ps.setString(6, attempt.batchId());
        ps.setString(7, normalize(attempt.status()));
        ps.setString(8, attempt.finalReason());
        ps.setString(9, attempt.errorCode());
        ps.setString(10, attempt.errorMessage());
        ps.setString(11, json(attempt.output()));
    }

    private static TaskReviewItem readItem(ResultSet rs) throws Exception {
        return new TaskReviewItem(
                rs.getString("message_id"),
                rs.getString("event_code"),
                rs.getString("status"),
                rs.getString("final_reason"),
                rs.getString("payload_ref"),
                rs.getInt("retry_count"),
                rs.getInt("max_retry_count"),
                localDateTime(rs, "create_time"),
                localDateTime(rs, "assigned_time"),
                localDateTime(rs, "start_time"),
                localDateTime(rs, "complete_time"),
                localDateTime(rs, "update_time"),
                map(rs.getString("input_json")),
                rs.getString("worker_id"),
                rs.getString("batch_id"),
                rs.getString("attempt_id"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                map(rs.getString("output_json"))
        );
    }

    private static TaskReviewAttempt readAttempt(ResultSet rs) throws Exception {
        return new TaskReviewAttempt(
                rs.getString("attempt_id"),
                rs.getString("task_id"),
                rs.getString("message_id"),
                rs.getInt("attempt_no"),
                rs.getString("worker_id"),
                rs.getString("batch_id"),
                rs.getString("status"),
                rs.getString("final_reason"),
                rs.getString("error_code"),
                rs.getString("error_message"),
                map(rs.getString("output_json"))
        );
    }

    private static void setTimestamp(PreparedStatement ps, int index, LocalDateTime value) throws Exception {
        if (value == null) {
            ps.setTimestamp(index, null);
        } else {
            ps.setTimestamp(index, Timestamp.valueOf(value));
        }
    }

    private static LocalDateTime localDateTime(ResultSet rs, String column) throws Exception {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toLocalDateTime();
    }

    private static String json(Map<String, Object> value) throws Exception {
        return value == null || value.isEmpty() ? null : MAPPER.writeValueAsString(value);
    }

    private static Map<String, Object> map(String json) throws Exception {
        return json == null || json.isBlank() ? null : MAPPER.readValue(json, MAP_TYPE);
    }

    private static String normalize(String status) {
        return status == null ? null : status.trim().toUpperCase(java.util.Locale.ROOT);
    }

    private static boolean isBlank(String value) {
        return value == null || value.isBlank();
    }
}
