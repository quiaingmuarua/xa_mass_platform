package com.xa.mass.api.auth.iam;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

public class InMemoryUserRolePermissionStore implements UserRolePermissionStore {

    private final Map<String, UserRecord> usersById;
    private final Map<String, RoleRecord> rolesById;
    private final List<UserRoleBindingRecord> bindings;
    private final List<String> permissionNames;

    public InMemoryUserRolePermissionStore() {
        this(
                UserRolePermissionBootstrapDefaults.users(),
                UserRolePermissionBootstrapDefaults.roles(),
                UserRolePermissionBootstrapDefaults.bindings(),
                UserRolePermissionBootstrapDefaults.permissions()
        );
    }

    public InMemoryUserRolePermissionStore(List<UserRecord> users,
                                           List<RoleRecord> roles,
                                           List<UserRoleBindingRecord> bindings,
                                           List<String> permissionNames) {
        this.usersById = copyByUserId(users);
        this.rolesById = copyByRoleId(roles);
        this.bindings = new ArrayList<>(Objects.requireNonNullElse(bindings, List.of()));
        this.permissionNames = List.copyOf(Objects.requireNonNullElse(permissionNames, List.of()));
    }

    public static InMemoryUserRolePermissionStore bootstrapDefaults() {
        return new InMemoryUserRolePermissionStore();
    }

    @Override
    public synchronized List<UserRecord> listUsers() {
        return usersById.values().stream()
                .sorted(Comparator.comparing(UserRecord::userId))
                .toList();
    }

    @Override
    public synchronized UserRecord getUser(String userId) {
        if (userId == null || userId.isBlank()) {
            return null;
        }
        return usersById.get(userId.trim());
    }

    @Override
    public synchronized UserRecord createUser(UserRecord user) {
        UserRecord normalized = Objects.requireNonNull(user, "user");
        String userId = requireNonBlank(normalized.userId(), "userId");
        if (usersById.containsKey(userId)) {
            throw new IllegalArgumentException("user already exists: " + userId);
        }
        usersById.put(userId, normalized);
        return normalized;
    }

    @Override
    public synchronized UserRecord updateUser(UserRecord user) {
        UserRecord normalized = Objects.requireNonNull(user, "user");
        String userId = requireNonBlank(normalized.userId(), "userId");
        if (!usersById.containsKey(userId)) {
            return null;
        }
        usersById.put(userId, normalized);
        return normalized;
    }

    @Override
    public synchronized List<RoleRecord> listRoles() {
        return rolesById.values().stream()
                .sorted(Comparator.comparing(RoleRecord::roleId))
                .toList();
    }

    @Override
    public synchronized RoleRecord getRole(String roleId) {
        if (roleId == null || roleId.isBlank()) {
            return null;
        }
        return rolesById.get(roleId.trim());
    }

    @Override
    public synchronized RoleRecord createRole(RoleRecord role) {
        RoleRecord normalized = normalizeRole(Objects.requireNonNull(role, "role"));
        if (rolesById.containsKey(normalized.roleId())) {
            throw new IllegalArgumentException("role already exists: " + normalized.roleId());
        }
        rolesById.put(normalized.roleId(), normalized);
        return normalized;
    }

    @Override
    public synchronized RoleRecord updateRole(RoleRecord role) {
        RoleRecord normalized = normalizeRole(Objects.requireNonNull(role, "role"));
        if (!rolesById.containsKey(normalized.roleId())) {
            return null;
        }
        rolesById.put(normalized.roleId(), normalized);
        return normalized;
    }

    @Override
    public synchronized List<UserRoleBindingRecord> listRoleBindings(String userId) {
        if (userId == null || userId.isBlank()) {
            return List.of();
        }
        String normalizedUserId = userId.trim();
        return bindings.stream()
                .filter(binding -> normalizedUserId.equals(binding.userId()))
                .sorted(Comparator.comparing(UserRoleBindingRecord::roleId))
                .toList();
    }

    @Override
    public synchronized UserRoleBindingRecord bindRole(UserRoleBindingRecord binding) {
        UserRoleBindingRecord normalized = Objects.requireNonNull(binding, "binding");
        String userId = requireNonBlank(normalized.userId(), "userId");
        String roleId = requireNonBlank(normalized.roleId(), "roleId");
        if (!usersById.containsKey(userId)) {
            throw new IllegalArgumentException("user does not exist: " + userId);
        }
        if (!rolesById.containsKey(roleId)) {
            throw new IllegalArgumentException("role does not exist: " + roleId);
        }
        for (UserRoleBindingRecord existing : bindings) {
            if (userId.equals(existing.userId()) && roleId.equals(existing.roleId())) {
                return existing;
            }
        }
        bindings.add(normalized);
        return normalized;
    }

    @Override
    public synchronized boolean unbindRole(String userId, String roleId) {
        String normalizedUserId = requireNonBlank(userId, "userId");
        String normalizedRoleId = requireNonBlank(roleId, "roleId");
        return bindings.removeIf(binding ->
                normalizedUserId.equals(binding.userId()) && normalizedRoleId.equals(binding.roleId()));
    }

    @Override
    public List<String> listPermissionNames() {
        return permissionNames;
    }

    private static Map<String, UserRecord> copyByUserId(List<UserRecord> users) {
        Map<String, UserRecord> copy = new LinkedHashMap<>();
        for (UserRecord user : Objects.requireNonNullElse(users, List.<UserRecord>of())) {
            if (user != null && user.userId() != null && !user.userId().isBlank()) {
                copy.put(user.userId().trim(), user);
            }
        }
        return copy;
    }

    private static Map<String, RoleRecord> copyByRoleId(List<RoleRecord> roles) {
        Map<String, RoleRecord> copy = new LinkedHashMap<>();
        for (RoleRecord role : Objects.requireNonNullElse(roles, List.<RoleRecord>of())) {
            if (role != null && role.roleId() != null && !role.roleId().isBlank()) {
                copy.put(role.roleId().trim(), role);
            }
        }
        return copy;
    }

    private String requireNonBlank(String value, String fieldName) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(fieldName + " must not be blank");
        }
        return value.trim();
    }

    private RoleRecord normalizeRole(RoleRecord role) {
        String roleId = requireNonBlank(role.roleId(), "roleId");
        String name = requireNonBlank(role.name(), "name");
        Set<String> permissions = new LinkedHashSet<>();
        for (String permission : Objects.requireNonNullElse(role.permissions(), Set.<String>of())) {
            String normalized = requireNonBlank(permission, "permission");
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

    private String normalizeOptional(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

}
