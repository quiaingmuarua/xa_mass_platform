package com.xa.mass.api.auth.operator;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

public final class JdbcOperatorCredentialStore implements OperatorCredentialStore {

    private final DataSource dataSource;

    public JdbcOperatorCredentialStore(DataSource dataSource) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
    }

    @Override
    public List<OperatorCredentialRecord> list() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_operator_credential
                     ORDER BY user_id
                     """);
             var rs = ps.executeQuery()) {
            List<OperatorCredentialRecord> credentials = new ArrayList<>();
            while (rs.next()) {
                credentials.add(read(rs));
            }
            return credentials;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list operator credentials", e);
        }
    }

    @Override
    public OperatorCredentialRecord get(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        String normalized = userId.trim();
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT * FROM xa_operator_credential WHERE user_id = ?")) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? read(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read operator credential: " + normalized, e);
        }
    }

    @Override
    public OperatorCredentialRecord upsert(OperatorCredentialRecord credential) {
        OperatorCredentialRecord normalized = Objects.requireNonNull(credential, "credential");
        if (get(normalized.userId()) == null) {
            return insert(normalized);
        }
        return update(normalized);
    }

    private OperatorCredentialRecord insert(OperatorCredentialRecord credential) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO xa_operator_credential(
                       user_id, password_hash, hash_algorithm, status, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            bind(ps, credential);
            ps.executeUpdate();
            return credential;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to insert operator credential: " + credential.userId(), e);
        }
    }

    private OperatorCredentialRecord update(OperatorCredentialRecord credential) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     UPDATE xa_operator_credential
                     SET password_hash = ?, hash_algorithm = ?, status = ?, updated_at = ?
                     WHERE user_id = ?
                     """)) {
            ps.setString(1, credential.passwordHash());
            ps.setString(2, credential.hashAlgorithm());
            ps.setString(3, credential.status().name());
            ps.setTimestamp(4, timestamp(credential.updatedAt()));
            ps.setString(5, credential.userId());
            ps.executeUpdate();
            return credential;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update operator credential: " + credential.userId(), e);
        }
    }

    private void bind(java.sql.PreparedStatement ps, OperatorCredentialRecord credential) throws Exception {
        ps.setString(1, credential.userId());
        ps.setString(2, credential.passwordHash());
        ps.setString(3, credential.hashAlgorithm());
        ps.setString(4, credential.status().name());
        ps.setTimestamp(5, timestamp(credential.createdAt()));
        ps.setTimestamp(6, timestamp(credential.updatedAt()));
    }

    private OperatorCredentialRecord read(ResultSet rs) throws Exception {
        return new OperatorCredentialRecord(
                rs.getString("user_id"),
                rs.getString("password_hash"),
                rs.getString("hash_algorithm"),
                OperatorCredentialStatus.valueOf(rs.getString("status")),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
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
