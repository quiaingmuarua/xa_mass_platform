package com.xa.mass.api.auth.apikey;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcApiKeyApplicationStore implements ApiKeyApplicationStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final DataSource dataSource;

    public JdbcApiKeyApplicationStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public ApiKeyApplicationRecord create(ApiKeyApplicationRecord record) {
        ApiKeyApplicationRecord normalized = Objects.requireNonNull(record, "record");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO xa_api_key_application(
                       application_id, applicant_user_id, applicant_name, requested_principal_id,
                       requested_user_id, requested_project_scopes_json, requested_event_scopes_json,
                       requested_permissions_json, purpose, status, review_reason, reviewed_by,
                       created_at, reviewed_at, attributes_json)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bind(ps, normalized);
            ps.executeUpdate();
            return normalized;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create API key application: " + normalized.applicationId(), e);
        }
    }

    @Override
    public ApiKeyApplicationRecord get(String applicationId) {
        if (applicationId == null) {
            return null;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT * FROM xa_api_key_application WHERE application_id = ?")) {
            ps.setString(1, applicationId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read API key application: " + applicationId, e);
        }
    }

    @Override
    public List<ApiKeyApplicationRecord> list() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_api_key_application
                     ORDER BY created_at, application_id
                     """);
             var rs = ps.executeQuery()) {
            List<ApiKeyApplicationRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(read(rs));
            }
            return records;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list API key applications", e);
        }
    }

    @Override
    public ApiKeyApplicationRecord markApproved(String applicationId, String reviewedBy, String reviewReason) {
        return transition(applicationId, ApiKeyApplicationStatus.APPROVED, reviewedBy, reviewReason);
    }

    @Override
    public ApiKeyApplicationRecord markRejected(String applicationId, String reviewedBy, String reviewReason) {
        return transition(applicationId, ApiKeyApplicationStatus.REJECTED, reviewedBy, reviewReason);
    }

    private ApiKeyApplicationRecord transition(String applicationId,
                                               ApiKeyApplicationStatus status,
                                               String reviewedBy,
                                               String reviewReason) {
        ApiKeyApplicationRecord existing = get(applicationId);
        if (existing == null) {
            return null;
        }
        if (existing.status() != ApiKeyApplicationStatus.PENDING) {
            throw new IllegalArgumentException("API key application is not pending: " + applicationId);
        }
        ApiKeyApplicationRecord updated = new ApiKeyApplicationRecord(
                existing.applicationId(),
                existing.applicantUserId(),
                existing.applicantName(),
                existing.requestedPrincipalId(),
                existing.requestedUserId(),
                existing.requestedProjectScopes(),
                existing.requestedEventScopes(),
                existing.requestedPermissions(),
                existing.purpose(),
                status,
                normalize(reviewReason),
                normalize(reviewedBy),
                existing.createdAt(),
                Instant.now(),
                existing.attributes()
        );
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     UPDATE xa_api_key_application
                     SET status = ?, review_reason = ?, reviewed_by = ?, reviewed_at = ?
                     WHERE application_id = ? AND status = ?
                     """)) {
            ps.setString(1, updated.status().name());
            ps.setString(2, updated.reviewReason());
            ps.setString(3, updated.reviewedBy());
            ps.setTimestamp(4, timestamp(updated.reviewedAt()));
            ps.setString(5, updated.applicationId());
            ps.setString(6, ApiKeyApplicationStatus.PENDING.name());
            if (ps.executeUpdate() == 0) {
                throw new IllegalArgumentException("API key application is not pending: " + applicationId);
            }
            return updated;
        } catch (Exception e) {
            if (e instanceof IllegalArgumentException illegalArgumentException) {
                throw illegalArgumentException;
            }
            throw new IllegalStateException("Failed to update API key application: " + applicationId, e);
        }
    }

    private void bind(java.sql.PreparedStatement ps, ApiKeyApplicationRecord record) throws Exception {
        ps.setString(1, record.applicationId());
        ps.setString(2, record.applicantUserId());
        ps.setString(3, record.applicantName());
        ps.setString(4, record.requestedPrincipalId());
        ps.setString(5, record.requestedUserId());
        ps.setString(6, json(record.requestedProjectScopes()));
        ps.setString(7, json(record.requestedEventScopes()));
        ps.setString(8, json(record.requestedPermissions()));
        ps.setString(9, record.purpose());
        ps.setString(10, record.status().name());
        ps.setString(11, record.reviewReason());
        ps.setString(12, record.reviewedBy());
        ps.setTimestamp(13, timestamp(record.createdAt()));
        ps.setTimestamp(14, timestamp(record.reviewedAt()));
        ps.setString(15, json(record.attributes()));
    }

    private ApiKeyApplicationRecord read(ResultSet rs) throws Exception {
        return new ApiKeyApplicationRecord(
                rs.getString("application_id"),
                rs.getString("applicant_user_id"),
                rs.getString("applicant_name"),
                rs.getString("requested_principal_id"),
                rs.getString("requested_user_id"),
                readStringList(rs.getString("requested_project_scopes_json")),
                readStringList(rs.getString("requested_event_scopes_json")),
                readStringList(rs.getString("requested_permissions_json")),
                rs.getString("purpose"),
                ApiKeyApplicationStatus.valueOf(rs.getString("status")),
                rs.getString("review_reason"),
                rs.getString("reviewed_by"),
                instant(rs, "created_at"),
                instant(rs, "reviewed_at"),
                readStringMap(rs.getString("attributes_json"))
        );
    }

    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize API key application value", e);
        }
    }

    private List<String> readStringList(String json) throws Exception {
        return json == null || json.isBlank() ? List.of() : List.copyOf(MAPPER.readValue(json, STRING_LIST));
    }

    private Map<String, String> readStringMap(String json) throws Exception {
        return json == null || json.isBlank() ? Map.of() : Map.copyOf(MAPPER.readValue(json, STRING_MAP));
    }

    private Timestamp timestamp(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private Instant instant(ResultSet rs, String column) throws Exception {
        Timestamp timestamp = rs.getTimestamp(column);
        return timestamp == null ? null : timestamp.toInstant();
    }

    private String normalize(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
