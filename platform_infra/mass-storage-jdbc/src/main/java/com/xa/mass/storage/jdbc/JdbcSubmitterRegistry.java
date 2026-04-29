package com.xa.mass.server.storage;

import com.xa.mass.sdk.auth.CredentialHashing;
import com.xa.mass.sdk.auth.InMemorySubmitterRegistry;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.SubmitterMetadata;
import com.xa.mass.sdk.auth.SubmitterRegistration;
import com.xa.mass.sdk.auth.SubmitterRegistry;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * JDBC-backed low-frequency principal credential registry. Durable control
 * truth lives in the database; runtime authentication reads from the loaded
 * in-process projection.
 */
public final class JdbcSubmitterRegistry extends JdbcStorageSupport implements SubmitterRegistry {

    private final JdbcDialect dialect;
    private final InMemorySubmitterRegistry runtimeProjection = new InMemorySubmitterRegistry();
    private boolean loadedFromDb;

    public JdbcSubmitterRegistry(DataSource dataSource, JdbcDialect dialect) {
        super(dataSource);
        this.dialect = Objects.requireNonNull(dialect, "dialect");
    }

    @Override
    public synchronized void register(SubmitterRegistration submitterRegistration) {
        SubmitterRegistration registration = Objects.requireNonNull(submitterRegistration, "submitterRegistration");
        ensureLoaded();
        String credentialHash = CredentialHashing.sha256(registration.getCredential());
        String existingPrincipalId = findPrincipalIdByCredentialHash(credentialHash);
        if (existingPrincipalId != null && !existingPrincipalId.equals(registration.getPrincipalId())) {
            throw new IllegalArgumentException("credential is already assigned to another submitter");
        }
        SubmitterMetadata metadata = registration.toMetadata();
        String metadataJson = json(StoredSubmitterDocument.from(metadata));
        try (var conn = connection(); var ps = conn.prepareStatement(dialect.principalUpsertSql())) {
            ps.setString(1, registration.getPrincipalId());
            ps.setString(2, registration.getPrincipalType().name());
            ps.setString(3, credentialHash);
            ps.setString(4, registration.getKeyPrefix());
            ps.setString(5, registration.getUserId());
            ps.setString(6, registration.getProjectScope());
            ps.setBoolean(7, registration.isEnabled());
            ps.setString(8, metadataJson);
            ps.executeUpdate();
        } catch (Exception e) {
            throw new IllegalStateException("Failed to save submitter " + registration.getPrincipalId(), e);
        }
        runtimeProjection.register(registration);
    }

    @Override
    public synchronized List<SubmitterMetadata> listSubmitters() {
        ensureLoaded();
        return runtimeProjection.listSubmitters();
    }

    @Override
    public synchronized SubmitterMetadata getSubmitter(String principalId) {
        ensureLoaded();
        return runtimeProjection.getSubmitter(principalId);
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
            runtimeProjection.loadDurable(record.metadata(), record.credentialHash());
        }
        loadedFromDb = true;
    }

    private List<StoredPrincipalRecord> queryStoredPrincipals() {
        try (var conn = connection(); var ps = conn.prepareStatement(
                "SELECT principal_id, credential_hash, json FROM xa_principal ORDER BY principal_id"
        )) {
            List<StoredPrincipalRecord> result = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    SubmitterMetadata metadata = readJson(rs.getString("json"), StoredSubmitterDocument.class).toMetadata();
                    result.add(new StoredPrincipalRecord(
                            rs.getString("principal_id"),
                            rs.getString("credential_hash"),
                            metadata
                    ));
                }
            }
            return result;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to load submitters from JDBC storage", e);
        }
    }

    private String findPrincipalIdByCredentialHash(String credentialHash) {
        try (var conn = connection(); var ps = conn.prepareStatement(
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

    private record StoredPrincipalRecord(String principalId, String credentialHash, SubmitterMetadata metadata) {}

    private static final class StoredSubmitterDocument {
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

        static StoredSubmitterDocument from(SubmitterMetadata metadata) {
            StoredSubmitterDocument document = new StoredSubmitterDocument();
            document.principalId = metadata.getPrincipalId();
            document.principalType = metadata.getPrincipalType().name();
            document.keyPrefix = metadata.getKeyPrefix();
            document.userId = metadata.getUserId();
            document.projectScope = metadata.getProjectScope();
            document.permissions = metadata.getPermissions();
            document.projectScopes = metadata.getProjectScopes();
            document.eventScopes = metadata.getEventScopes();
            document.enabled = metadata.isEnabled();
            document.attributes = metadata.getAttributes();
            return document;
        }

        SubmitterMetadata toMetadata() {
            return SubmitterMetadata.builder()
                    .principalId(principalId)
                    .principalType(principalType == null ? null : com.xa.mass.sdk.auth.PrincipalType.valueOf(principalType))
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
