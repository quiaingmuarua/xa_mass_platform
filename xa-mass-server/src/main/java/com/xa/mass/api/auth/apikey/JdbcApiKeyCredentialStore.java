package com.xa.mass.api.auth.apikey;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.sdk.auth.PrincipalContext;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;

public final class JdbcApiKeyCredentialStore implements ApiKeyCredentialStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final DataSource dataSource;

    public JdbcApiKeyCredentialStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public ApiKeyCredentialRecord create(ApiKeyCredentialRecord record) {
        ApiKeyCredentialRecord normalized = Objects.requireNonNull(record, "record");
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO xa_api_key_credential(
                       key_id, principal_id, created_for_user_id, key_prefix, credential_hash,
                       project_scope_mode, project_scopes_json, event_scope_mode, event_scopes_json,
                       permissions_json, status, application_id, created_by, created_at, expires_at,
                       revoked_at, revoked_by, revoke_reason, attributes_json)
                     VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bind(ps, normalized);
            ps.executeUpdate();
            return normalized;
        } catch (Exception e) {
            throw new IllegalArgumentException("Failed to create API key credential: " + normalized.keyId(), e);
        }
    }

    @Override
    public ApiKeyCredentialRecord get(String keyId) {
        return find("SELECT * FROM xa_api_key_credential WHERE key_id = ?", keyId);
    }

    @Override
    public ApiKeyCredentialRecord getByPrincipalId(String principalId) {
        return find("SELECT * FROM xa_api_key_credential WHERE principal_id = ?", principalId);
    }

    @Override
    public List<ApiKeyCredentialRecord> list() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_api_key_credential
                     ORDER BY created_at, key_id
                     """);
             var rs = ps.executeQuery()) {
            List<ApiKeyCredentialRecord> records = new ArrayList<>();
            while (rs.next()) {
                records.add(read(rs));
            }
            return records;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list API key credentials", e);
        }
    }

    @Override
    public ApiKeyCredentialRecord revoke(String keyId, String revokedBy, String revokeReason) {
        ApiKeyCredentialRecord existing = get(keyId);
        if (existing == null) {
            return null;
        }
        if (existing.status() == ApiKeyCredentialStatus.REVOKED) {
            return existing;
        }
        ApiKeyCredentialRecord revoked = statusRecord(
                existing,
                ApiKeyCredentialStatus.REVOKED,
                normalize(revokedBy),
                normalize(revokeReason)
        );
        updateStatus(revoked);
        return revoked;
    }

    @Override
    public List<ApiKeyCredentialRecord> disableByUserId(String userId, String disabledBy, String disableReason) {
        String normalizedUserId = normalize(userId);
        if (normalizedUserId == null) {
            return List.of();
        }
        List<ApiKeyCredentialRecord> disabled = new ArrayList<>();
        for (ApiKeyCredentialRecord existing : listByCreatedForUserId(normalizedUserId)) {
            if (existing.status() == ApiKeyCredentialStatus.REVOKED
                    || existing.status() == ApiKeyCredentialStatus.DISABLED) {
                continue;
            }
            ApiKeyCredentialRecord updated = statusRecord(
                    existing,
                    ApiKeyCredentialStatus.DISABLED,
                    normalize(disabledBy),
                    normalize(disableReason)
            );
            updateStatus(updated);
            disabled.add(updated);
        }
        return disabled;
    }

    @Override
    public ApiKeyCredentialRecord expire(String keyId) {
        ApiKeyCredentialRecord existing = get(keyId);
        if (existing == null) {
            return null;
        }
        if (existing.status() != ApiKeyCredentialStatus.ACTIVE) {
            return existing;
        }
        ApiKeyCredentialRecord expired = statusRecord(
                existing,
                ApiKeyCredentialStatus.EXPIRED,
                "system",
                "expiresAt reached"
        );
        updateStatus(expired);
        return expired;
    }

    private ApiKeyCredentialRecord find(String sql, String value) {
        if (value == null) {
            return null;
        }
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(sql)) {
            ps.setString(1, value);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read API key credential", e);
        }
    }

    private List<ApiKeyCredentialRecord> listByCreatedForUserId(String userId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_api_key_credential
                     WHERE created_for_user_id = ?
                     ORDER BY created_at, key_id
                     """)) {
            ps.setString(1, userId);
            List<ApiKeyCredentialRecord> records = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    records.add(read(rs));
                }
            }
            return records;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list API key credentials by user: " + userId, e);
        }
    }

    private void updateStatus(ApiKeyCredentialRecord record) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     UPDATE xa_api_key_credential
                     SET status = ?, revoked_at = ?, revoked_by = ?, revoke_reason = ?
                     WHERE key_id = ?
                     """)) {
            ps.setString(1, record.status().name());
            ps.setTimestamp(2, timestamp(record.revokedAt()));
            ps.setString(3, record.revokedBy());
            ps.setString(4, record.revokeReason());
            ps.setString(5, record.keyId());
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update API key credential status: " + record.keyId(), e);
        }
    }

    private ApiKeyCredentialRecord statusRecord(ApiKeyCredentialRecord existing,
                                                ApiKeyCredentialStatus status,
                                                String revokedBy,
                                                String revokeReason) {
        return new ApiKeyCredentialRecord(
                existing.keyId(),
                existing.principalId(),
                existing.createdForUserId(),
                existing.keyPrefix(),
                existing.credentialHash(),
                existing.projectScopes(),
                existing.eventScopes(),
                existing.permissions(),
                status,
                existing.applicationId(),
                existing.createdBy(),
                existing.createdAt(),
                existing.expiresAt(),
                Instant.now(),
                revokedBy,
                revokeReason,
                existing.attributes()
        );
    }

    private void bind(java.sql.PreparedStatement ps, ApiKeyCredentialRecord record) throws Exception {
        ps.setString(1, record.keyId());
        ps.setString(2, record.principalId());
        ps.setString(3, record.createdForUserId());
        ps.setString(4, record.keyPrefix());
        ps.setString(5, record.credentialHash());
        ps.setString(6, scopeMode(record.projectScopes()));
        ps.setString(7, json(record.projectScopes()));
        ps.setString(8, scopeMode(record.eventScopes()));
        ps.setString(9, json(record.eventScopes()));
        ps.setString(10, json(record.permissions()));
        ps.setString(11, record.status().name());
        ps.setString(12, record.applicationId());
        ps.setString(13, record.createdBy());
        ps.setTimestamp(14, timestamp(record.createdAt()));
        ps.setTimestamp(15, timestamp(record.expiresAt()));
        ps.setTimestamp(16, timestamp(record.revokedAt()));
        ps.setString(17, record.revokedBy());
        ps.setString(18, record.revokeReason());
        ps.setString(19, json(record.attributes()));
    }

    private ApiKeyCredentialRecord read(ResultSet rs) throws Exception {
        return new ApiKeyCredentialRecord(
                rs.getString("key_id"),
                rs.getString("principal_id"),
                rs.getString("created_for_user_id"),
                rs.getString("key_prefix"),
                rs.getString("credential_hash"),
                readStringList(rs.getString("project_scopes_json")),
                readStringList(rs.getString("event_scopes_json")),
                readStringList(rs.getString("permissions_json")),
                ApiKeyCredentialStatus.valueOf(rs.getString("status")),
                rs.getString("application_id"),
                rs.getString("created_by"),
                instant(rs, "created_at"),
                instant(rs, "expires_at"),
                instant(rs, "revoked_at"),
                rs.getString("revoked_by"),
                rs.getString("revoke_reason"),
                readStringMap(rs.getString("attributes_json"))
        );
    }

    private String scopeMode(List<String> scopes) {
        if (scopes == null || scopes.isEmpty()) {
            return "OMITTED";
        }
        return scopes.stream().anyMatch(PrincipalContext.WILDCARD_SCOPE::equals) ? "WILDCARD" : "BOUNDED";
    }

    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize API key credential value", e);
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
