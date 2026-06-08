package com.xa.mass.server.auth.jdbc;

import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.sdk.auth.CredentialHashing;
import com.xa.mass.sdk.auth.CredentialPrincipalProfile;
import com.xa.mass.sdk.auth.CredentialPrincipalRegistration;
import com.xa.mass.sdk.auth.CredentialPrincipalStore;
import com.xa.mass.sdk.auth.InMemoryCredentialPrincipalStore;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalType;
import com.xa.mass.storage.jdbc.JdbcStorageMode;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * Server-side JDBC persistence for credential-principal auth truth.
 */
public final class JdbcCredentialPrincipalStore implements CredentialPrincipalStore {

    private final DataSource dataSource;
    private final JdbcStorageMode mode;
    private final ObjectMapper mapper = new ObjectMapper()
            .configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    private final InMemoryCredentialPrincipalStore runtimeProjection = new InMemoryCredentialPrincipalStore();
    private boolean loadedFromDb;

    public JdbcCredentialPrincipalStore(DataSource dataSource, JdbcStorageMode mode) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.mode = Objects.requireNonNull(mode, "mode");
        if (!mode.isJdbc()) {
            throw new IllegalArgumentException("JdbcCredentialPrincipalStore requires a JDBC storage mode");
        }
    }

    @Override
    public synchronized void registerCredentialPrincipal(CredentialPrincipalRegistration registration) {
        CredentialPrincipalRegistration normalized = Objects.requireNonNull(registration, "registration");
        ensureLoaded();
        String credentialHash = CredentialHashing.sha256(normalized.getCredential());
        String existingPrincipalId = findPrincipalIdByCredentialHash(credentialHash);
        if (existingPrincipalId != null && !existingPrincipalId.equals(normalized.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another principal");
        }
        CredentialPrincipalProfile profile = normalized.toProfile();
        String profileJson = json(StoredCredentialPrincipalDocument.from(profile));
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(principalUpsertSql())) {
            ps.setString(1, normalized.getPrincipalId());
            ps.setString(2, normalized.getPrincipalType().name());
            ps.setString(3, credentialHash);
            ps.setString(4, normalized.getKeyPrefix());
            ps.setString(5, normalized.getUserId());
            ps.setString(6, normalized.getProjectScope());
            ps.setBoolean(7, normalized.isEnabled());
            ps.setString(8, profileJson);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save credential principal " + normalized.getPrincipalId(), e);
        }
        runtimeProjection.registerCredentialPrincipal(normalized);
    }

    @Override
    public void projectCredential(CredentialPrincipalRegistration registration) {
        registerCredentialPrincipal(registration);
    }

    @Override
    public synchronized boolean hasProjectedCredential(String principalId) {
        ensureLoaded();
        return runtimeProjection.getCredentialPrincipal(principalId) != null;
    }

    @Override
    public synchronized List<CredentialPrincipalProfile> listCredentialPrincipals() {
        ensureLoaded();
        return runtimeProjection.listCredentialPrincipals();
    }

    @Override
    public synchronized CredentialPrincipalProfile getCredentialPrincipal(String principalId) {
        ensureLoaded();
        return runtimeProjection.getCredentialPrincipal(principalId);
    }

    @Override
    public synchronized PrincipalContext getPrincipal(String principalId) {
        ensureLoaded();
        return runtimeProjection.getPrincipal(principalId);
    }

    @Override
    public synchronized PrincipalContext authenticate(String credential) {
        ensureLoaded();
        return runtimeProjection.authenticate(credential);
    }

    private synchronized void ensureLoaded() {
        if (loadedFromDb) {
            return;
        }
        for (StoredPrincipalRecord record : queryStoredPrincipals()) {
            runtimeProjection.loadDurable(record.profile(), record.credentialHash());
        }
        loadedFromDb = true;
    }

    private List<StoredPrincipalRecord> queryStoredPrincipals() {
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "SELECT principal_id, credential_hash, json FROM xa_principal ORDER BY principal_id"
        )) {
            List<StoredPrincipalRecord> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    CredentialPrincipalProfile profile = readJson(
                            rs.getString("json"),
                            StoredCredentialPrincipalDocument.class
                    ).toProfile();
                    result.add(new StoredPrincipalRecord(
                            rs.getString("principal_id"),
                            rs.getString("credential_hash"),
                            profile
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load credential principals from JDBC storage", e);
        }
    }

    private String findPrincipalIdByCredentialHash(String credentialHash) {
        try (var conn = dataSource.getConnection(); var ps = conn.prepareStatement(
                "SELECT principal_id FROM xa_principal WHERE credential_hash = ?"
        )) {
            ps.setString(1, credentialHash);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? rs.getString(1) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to query principal by credential hash", e);
        }
    }

    private String principalUpsertSql() {
        return switch (mode) {
            case JDBC_H2 -> """
                    MERGE INTO xa_principal KEY(principal_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """;
            case JDBC_POSTGRES -> """
                    INSERT INTO xa_principal(principal_id, principal_type, credential_hash, key_prefix, user_id, project_scope, enabled, json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT (principal_id) DO UPDATE SET
                      principal_type = EXCLUDED.principal_type,
                      credential_hash = EXCLUDED.credential_hash,
                      key_prefix = EXCLUDED.key_prefix,
                      user_id = EXCLUDED.user_id,
                      project_scope = EXCLUDED.project_scope,
                      enabled = EXCLUDED.enabled,
                      json = EXCLUDED.json
                    """;
            case JDBC_SQLITE -> """
                    INSERT INTO xa_principal(principal_id, principal_type, credential_hash, key_prefix, user_id, project_scope, enabled, json)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    ON CONFLICT(principal_id) DO UPDATE SET
                      principal_type = excluded.principal_type,
                      credential_hash = excluded.credential_hash,
                      key_prefix = excluded.key_prefix,
                      user_id = excluded.user_id,
                      project_scope = excluded.project_scope,
                      enabled = excluded.enabled,
                      json = excluded.json
                    """;
            case MEMORY -> throw new IllegalStateException("memory mode does not use JDBC credential-principal persistence");
        };
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize credential principal profile", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to deserialize credential principal profile: " + type.getSimpleName(), e);
        }
    }

    private record StoredPrincipalRecord(String principalId,
                                         String credentialHash,
                                         CredentialPrincipalProfile profile) {
    }

    private static final class StoredCredentialPrincipalDocument {
        public String principalId;
        public String principalType;
        public String keyPrefix;
        public String userId;
        public String projectScope;
        public List<String> permissions;
        public List<String> projectScopes;
        public List<String> eventScopes;
        public boolean enabled;
        public java.util.Map<String, String> attributes;

        static StoredCredentialPrincipalDocument from(CredentialPrincipalProfile profile) {
            StoredCredentialPrincipalDocument document = new StoredCredentialPrincipalDocument();
            document.principalId = profile.getPrincipalId();
            document.principalType = profile.getPrincipalType().name();
            document.keyPrefix = profile.getKeyPrefix();
            document.userId = profile.getUserId();
            document.projectScope = profile.getProjectScope();
            document.permissions = profile.getPermissions();
            document.projectScopes = profile.getProjectScopes();
            document.eventScopes = profile.getEventScopes();
            document.enabled = profile.isEnabled();
            document.attributes = profile.getAttributes();
            return document;
        }

        CredentialPrincipalProfile toProfile() {
            return CredentialPrincipalProfile.builder()
                    .principalId(principalId)
                    .principalType(principalType == null ? null : PrincipalType.valueOf(principalType))
                    .keyPrefix(keyPrefix)
                    .userId(userId)
                    .projectScope(projectScope)
                    .permissions(permissions)
                    .projectScopes(projectScopes)
                    .eventScopes(eventScopes)
                    .enabled(enabled)
                    .attributes(attributes)
                    .build();
        }
    }
}
