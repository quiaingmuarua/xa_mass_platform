package com.xa.mass.api.auth.iam;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.xa.mass.api.auth.ApiPermissionNames;

import javax.sql.DataSource;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public final class JdbcUserRolePermissionStore implements UserRolePermissionStore {

    private static final ObjectMapper MAPPER = new ObjectMapper();
    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() { };
    private static final TypeReference<Map<String, String>> STRING_MAP = new TypeReference<>() { };

    private final DataSource dataSource;
    private final List<String> permissionNames;

    public JdbcUserRolePermissionStore(DataSource dataSource) {
        this(dataSource, true);
    }

    public JdbcUserRolePermissionStore(DataSource dataSource, boolean bootstrapDefaults) {
        this.dataSource = Objects.requireNonNull(dataSource, "dataSource");
        this.permissionNames = List.copyOf(ApiPermissionNames.ALL);
        if (bootstrapDefaults) {
            seedMissingDefaults();
        }
    }

    @Override
    public List<UserRecord> listUsers() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_iam_user
                     ORDER BY user_id
                     """);
             var rs = ps.executeQuery()) {
            List<UserRecord> users = new ArrayList<>();
            while (rs.next()) {
                users.add(readUser(rs));
            }
            return users;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list IAM users", e);
        }
    }

    @Override
    public UserRecord getUser(String userId) {
        String normalized = normalizeRequired(userId, "userId", false);
        if (normalized == null) {
            return null;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT * FROM xa_iam_user WHERE user_id = ?")) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readUser(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read IAM user: " + normalized, e);
        }
    }

    @Override
    public UserRecord createUser(UserRecord user) {
        UserRecord normalized = normalizeUser(Objects.requireNonNull(user, "user"));
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO xa_iam_user(
                       user_id, display_name, email, status, attributes_json, created_at, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?, ?)
                     """)) {
            bindUser(ps, normalized);
            ps.executeUpdate();
            return normalized;
        } catch (Exception e) {
            throw new IllegalArgumentException("user already exists: " + normalized.userId(), e);
        }
    }

    @Override
    public UserRecord updateUser(UserRecord user) {
        UserRecord normalized = normalizeUser(Objects.requireNonNull(user, "user"));
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     UPDATE xa_iam_user
                     SET display_name = ?, email = ?, status = ?, attributes_json = ?, updated_at = ?
                     WHERE user_id = ?
                     """)) {
            ps.setString(1, normalized.displayName());
            ps.setString(2, normalized.email());
            ps.setString(3, normalized.status().name());
            ps.setString(4, json(normalized.attributes()));
            ps.setTimestamp(5, timestamp(normalized.updatedAt()));
            ps.setString(6, normalized.userId());
            return ps.executeUpdate() == 0 ? null : normalized;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update IAM user: " + normalized.userId(), e);
        }
    }

    @Override
    public List<RoleRecord> listRoles() {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_iam_role
                     ORDER BY role_id
                     """);
             var rs = ps.executeQuery()) {
            List<RoleRecord> roles = new ArrayList<>();
            while (rs.next()) {
                roles.add(readRole(rs));
            }
            return roles;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list IAM roles", e);
        }
    }

    @Override
    public RoleRecord getRole(String roleId) {
        String normalized = normalizeRequired(roleId, "roleId", false);
        if (normalized == null) {
            return null;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("SELECT * FROM xa_iam_role WHERE role_id = ?")) {
            ps.setString(1, normalized);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readRole(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read IAM role: " + normalized, e);
        }
    }

    @Override
    public RoleRecord createRole(RoleRecord role) {
        RoleRecord normalized = normalizeRole(Objects.requireNonNull(role, "role"));
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO xa_iam_role(
                       role_id, name, description, permissions_json, system_role, updated_at)
                     VALUES (?, ?, ?, ?, ?, ?)
                     """)) {
            bindRole(ps, normalized);
            ps.executeUpdate();
            return normalized;
        } catch (Exception e) {
            throw new IllegalArgumentException("role already exists: " + normalized.roleId(), e);
        }
    }

    @Override
    public RoleRecord updateRole(RoleRecord role) {
        RoleRecord normalized = normalizeRole(Objects.requireNonNull(role, "role"));
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     UPDATE xa_iam_role
                     SET name = ?, description = ?, permissions_json = ?, system_role = ?, updated_at = ?
                     WHERE role_id = ?
                     """)) {
            ps.setString(1, normalized.name());
            ps.setString(2, normalized.description());
            ps.setString(3, json(List.copyOf(normalized.permissions())));
            ps.setBoolean(4, normalized.systemRole());
            ps.setTimestamp(5, timestamp(normalized.updatedAt()));
            ps.setString(6, normalized.roleId());
            return ps.executeUpdate() == 0 ? null : normalized;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to update IAM role: " + normalized.roleId(), e);
        }
    }

    @Override
    public List<UserRoleBindingRecord> listRoleBindings(String userId) {
        String normalized = normalizeRequired(userId, "userId", false);
        if (normalized == null) {
            return List.of();
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_iam_user_role
                     WHERE user_id = ?
                     ORDER BY role_id
                     """)) {
            ps.setString(1, normalized);
            List<UserRoleBindingRecord> bindings = new ArrayList<>();
            try (ResultSet rs = ps.executeQuery()) {
                while (rs.next()) {
                    bindings.add(readBinding(rs));
                }
            }
            return bindings;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to list IAM role bindings: " + normalized, e);
        }
    }

    @Override
    public UserRoleBindingRecord bindRole(UserRoleBindingRecord binding) {
        UserRoleBindingRecord normalized = normalizeBinding(Objects.requireNonNull(binding, "binding"));
        if (getUser(normalized.userId()) == null) {
            throw new IllegalArgumentException("user does not exist: " + normalized.userId());
        }
        if (getRole(normalized.roleId()) == null) {
            throw new IllegalArgumentException("role does not exist: " + normalized.roleId());
        }
        UserRoleBindingRecord existing = findBinding(normalized.userId(), normalized.roleId());
        if (existing != null) {
            return existing;
        }
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     INSERT INTO xa_iam_user_role(user_id, role_id, granted_by, granted_at)
                     VALUES (?, ?, ?, ?)
                     """)) {
            ps.setString(1, normalized.userId());
            ps.setString(2, normalized.roleId());
            ps.setString(3, normalized.grantedBy());
            ps.setTimestamp(4, timestamp(normalized.grantedAt()));
            ps.executeUpdate();
            return normalized;
        } catch (Exception e) {
            throw new IllegalStateException("Failed to bind IAM role: " + normalized, e);
        }
    }

    @Override
    public boolean unbindRole(String userId, String roleId) {
        String normalizedUserId = normalizeRequired(userId, "userId", true);
        String normalizedRoleId = normalizeRequired(roleId, "roleId", true);
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     DELETE FROM xa_iam_user_role
                     WHERE user_id = ? AND role_id = ?
                     """)) {
            ps.setString(1, normalizedUserId);
            ps.setString(2, normalizedRoleId);
            return ps.executeUpdate() > 0;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "Failed to unbind IAM role: " + normalizedUserId + "/" + normalizedRoleId, e);
        }
    }

    @Override
    public List<String> listPermissionNames() {
        return permissionNames;
    }

    private void seedMissingDefaults() {
        for (UserRecord user : UserRolePermissionBootstrapDefaults.users()) {
            if (getUser(user.userId()) == null) {
                createUser(user);
            }
        }
        for (RoleRecord role : UserRolePermissionBootstrapDefaults.roles()) {
            RoleRecord existing = getRole(role.roleId());
            if (existing == null) {
                createRole(role);
            } else if (existing.systemRole() && !sameSystemRole(existing, role)) {
                updateRole(role);
            }
        }
        for (UserRoleBindingRecord binding : UserRolePermissionBootstrapDefaults.bindings()) {
            bindRole(binding);
        }
    }

    private boolean sameSystemRole(RoleRecord existing, RoleRecord expected) {
        return Objects.equals(existing.name(), expected.name())
                && Objects.equals(existing.description(), expected.description())
                && Objects.equals(existing.permissions(), expected.permissions())
                && existing.systemRole() == expected.systemRole();
    }

    private UserRoleBindingRecord findBinding(String userId, String roleId) {
        try (var conn = dataSource.getConnection();
             var ps = conn.prepareStatement("""
                     SELECT * FROM xa_iam_user_role
                     WHERE user_id = ? AND role_id = ?
                     """)) {
            ps.setString(1, userId);
            ps.setString(2, roleId);
            try (ResultSet rs = ps.executeQuery()) {
                return rs.next() ? readBinding(rs) : null;
            }
        } catch (Exception e) {
            throw new IllegalStateException("Failed to read IAM role binding: " + userId + "/" + roleId, e);
        }
    }

    private UserRecord normalizeUser(UserRecord user) {
        String userId = normalizeRequired(user.userId(), "userId", true);
        return new UserRecord(
                userId,
                normalizeRequired(user.displayName(), "displayName", false),
                normalizeOptional(user.email()),
                user.status() == null ? UserStatus.ACTIVE : user.status(),
                user.attributes(),
                user.createdAt() == null ? Instant.now() : user.createdAt(),
                user.updatedAt() == null ? Instant.now() : user.updatedAt()
        );
    }

    private RoleRecord normalizeRole(RoleRecord role) {
        String roleId = normalizeRequired(role.roleId(), "roleId", true);
        String name = normalizeRequired(role.name(), "name", true);
        Set<String> permissions = new LinkedHashSet<>();
        for (String permission : Objects.requireNonNullElse(role.permissions(), Set.<String>of())) {
            String normalized = normalizeRequired(permission, "permission", true);
            if (!permissionNames.contains(normalized)) {
                throw new IllegalArgumentException("unknown permission: " + normalized);
            }
            permissions.add(normalized);
        }
        if (permissions.isEmpty()) {
            throw new IllegalArgumentException("permissions must not be empty");
        }
        return new RoleRecord(
                roleId,
                name,
                normalizeOptional(role.description()),
                permissions,
                role.systemRole(),
                role.updatedAt() == null ? Instant.now() : role.updatedAt()
        );
    }

    private UserRoleBindingRecord normalizeBinding(UserRoleBindingRecord binding) {
        return new UserRoleBindingRecord(
                normalizeRequired(binding.userId(), "userId", true),
                normalizeRequired(binding.roleId(), "roleId", true),
                normalizeRequired(binding.grantedBy(), "grantedBy", false),
                binding.grantedAt() == null ? Instant.now() : binding.grantedAt()
        );
    }

    private void bindUser(java.sql.PreparedStatement ps, UserRecord user) throws Exception {
        ps.setString(1, user.userId());
        ps.setString(2, user.displayName());
        ps.setString(3, user.email());
        ps.setString(4, user.status().name());
        ps.setString(5, json(user.attributes()));
        ps.setTimestamp(6, timestamp(user.createdAt()));
        ps.setTimestamp(7, timestamp(user.updatedAt()));
    }

    private void bindRole(java.sql.PreparedStatement ps, RoleRecord role) throws Exception {
        ps.setString(1, role.roleId());
        ps.setString(2, role.name());
        ps.setString(3, role.description());
        ps.setString(4, json(List.copyOf(role.permissions())));
        ps.setBoolean(5, role.systemRole());
        ps.setTimestamp(6, timestamp(role.updatedAt()));
    }

    private UserRecord readUser(ResultSet rs) throws Exception {
        return new UserRecord(
                rs.getString("user_id"),
                rs.getString("display_name"),
                rs.getString("email"),
                UserStatus.valueOf(rs.getString("status")),
                readStringMap(rs.getString("attributes_json")),
                instant(rs, "created_at"),
                instant(rs, "updated_at")
        );
    }

    private RoleRecord readRole(ResultSet rs) throws Exception {
        return new RoleRecord(
                rs.getString("role_id"),
                rs.getString("name"),
                rs.getString("description"),
                new LinkedHashSet<>(readStringList(rs.getString("permissions_json"))),
                rs.getBoolean("system_role"),
                instant(rs, "updated_at")
        );
    }

    private UserRoleBindingRecord readBinding(ResultSet rs) throws Exception {
        return new UserRoleBindingRecord(
                rs.getString("user_id"),
                rs.getString("role_id"),
                rs.getString("granted_by"),
                instant(rs, "granted_at")
        );
    }

    private String json(Object value) {
        try {
            return MAPPER.writeValueAsString(value == null ? Map.of() : value);
        } catch (Exception e) {
            throw new IllegalStateException("Failed to serialize IAM value", e);
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

    private String normalizeRequired(String value, String fieldName, boolean throwOnBlank) {
        if (value == null || value.isBlank()) {
            if (throwOnBlank) {
                throw new IllegalArgumentException(fieldName + " must not be blank");
            }
            return null;
        }
        return value.trim();
    }

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }
}
