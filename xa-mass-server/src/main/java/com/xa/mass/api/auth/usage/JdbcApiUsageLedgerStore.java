package com.xa.mass.api.auth.usage;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcApiUsageLedgerStore implements ApiUsageLedgerStore {

    private final DataSource dataSource;

    public JdbcApiUsageLedgerStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public ApiUsageLedgerRecord append(ApiUsageLedgerRecord record) {
        ApiUsageLedgerRecord normalized = Objects.requireNonNull(record, "record");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO xa_api_usage_ledger(
                       usage_id, key_id, principal_id, user_id, project, event_code, operation,
                       task_id, message_id, request_id, units, status, failure_reason,
                       failure_status, created_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bind(ps, normalized);
            ps.executeUpdate();
            return normalized;
        } catch (Exception e) {
            ApiUsageLedgerRecord existing = get(normalized.usageId());
            if (existing != null) {
                return existing;
            }
            throw new IllegalStateException("Failed to append API usage ledger record: " + normalized.usageId(), e);
        }
    }

    @Override
    public List<ApiUsageLedgerRecord> listByKeyId(String keyId) {
        return list("SELECT * FROM xa_api_usage_ledger WHERE key_id = ? ORDER BY created_at, usage_id", keyId);
    }

    @Override
    public List<ApiUsageLedgerRecord> listByPrincipalId(String principalId) {
        return list("SELECT * FROM xa_api_usage_ledger WHERE principal_id = ? ORDER BY created_at, usage_id", principalId);
    }

    private ApiUsageLedgerRecord get(String usageId) {
        if (usageId == null) {
            return null;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT * FROM xa_api_usage_ledger WHERE usage_id = ?")) {
            ps.setString(1, usageId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read API usage ledger record: " + usageId, e);
        }
    }

    private List<ApiUsageLedgerRecord> list(String sql, String value) {
        if (value == null || value.isBlank()) {
            return List.of();
        }
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, value.trim());
            List<ApiUsageLedgerRecord> records = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(read(rs));
                }
            }
            return records;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list API usage ledger records", e);
        }
    }

    private void bind(java.sql.PreparedStatement ps, ApiUsageLedgerRecord record) throws Exception {
        ps.setString(1, record.usageId());
        ps.setString(2, record.keyId());
        ps.setString(3, record.principalId());
        ps.setString(4, record.userId());
        ps.setString(5, record.project());
        ps.setString(6, record.eventCode());
        ps.setString(7, record.operation().name());
        ps.setString(8, record.taskId());
        ps.setString(9, record.messageId());
        ps.setString(10, record.requestId());
        ps.setLong(11, record.units());
        ps.setString(12, record.status().name());
        ps.setString(13, record.failureReason());
        if (record.failureStatus() == null) {
            ps.setObject(14, null);
        } else {
            ps.setInt(14, record.failureStatus());
        }
        ps.setTimestamp(15, timestamp(record.createdAt()));
    }

    private ApiUsageLedgerRecord read(ResultSet rs) throws Exception {
        Integer failureStatus = rs.getObject("failure_status") == null ? null : rs.getInt("failure_status");
        return new ApiUsageLedgerRecord(
                rs.getString("usage_id"),
                rs.getString("key_id"),
                rs.getString("principal_id"),
                rs.getString("user_id"),
                rs.getString("project"),
                rs.getString("event_code"),
                ApiUsageOperation.valueOf(rs.getString("operation")),
                rs.getString("task_id"),
                rs.getString("message_id"),
                rs.getString("request_id"),
                rs.getLong("units"),
                ApiUsageStatus.valueOf(rs.getString("status")),
                rs.getString("failure_reason"),
                failureStatus,
                instant(rs, "created_at")
        );
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(ResultSet rs, String column) throws Exception {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }
}
