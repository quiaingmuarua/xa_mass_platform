package com.xa.mass.api.auth.iam;

import com.xa.mass.api.auth.ApiPermissionNames;
import org.springframework.stereotype.Component;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

@Component
public class InMemoryUserRolePermissionStore implements UserRolePermissionStore {

    private static final Instant BOOTSTRAP_TIME = Instant.EPOCH;

    private final Map<String, UserRecord> usersById;
    private final Map<String, RoleRecord> rolesById;
    private final List<UserRoleBindingRecord> bindings;
    private final List<String> permissionNames;

    public InMemoryUserRolePermissionStore() {
        this(seedUsers(), seedRoles(), seedBindings(), ApiPermissionNames.ALL);
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

    private static List<UserRecord> seedUsers() {
        return List.of(
                new UserRecord(
                        "ops-admin",
                        "Ops Admin",
                        "ops-admin@example.internal",
                        UserStatus.ACTIVE,
                        Map.of(),
                        BOOTSTRAP_TIME,
                        BOOTSTRAP_TIME
                ),
                new UserRecord(
                        "ops-viewer",
                        "Ops Viewer",
                        "ops-viewer@example.internal",
                        UserStatus.ACTIVE,
                        Map.of(),
                        BOOTSTRAP_TIME,
                        BOOTSTRAP_TIME
                )
        );
    }

    private static List<RoleRecord> seedRoles() {
        Set<String> viewerPermissions = new LinkedHashSet<>();
        viewerPermissions.add(ApiPermissionNames.TASK_VIEW);
        viewerPermissions.add(ApiPermissionNames.WORKER_VIEW);
        viewerPermissions.add(ApiPermissionNames.RULE_VIEW);
        viewerPermissions.add(ApiPermissionNames.CONFIG_VIEW);
        viewerPermissions.add(ApiPermissionNames.AUDIT_VIEW);

        return List.of(
                new RoleRecord(
                        "OPS_ADMIN",
                        "Ops Admin",
                        "Full server operator access",
                        new LinkedHashSet<>(ApiPermissionNames.ALL),
                        true,
                        BOOTSTRAP_TIME
                ),
                new RoleRecord(
                        "OPS_VIEWER",
                        "Ops Viewer",
                        "Read-only operational visibility",
                        viewerPermissions,
                        true,
                        BOOTSTRAP_TIME
                ),
                new RoleRecord(
                        "API_KEY_REVIEWER",
                        "API Key Reviewer",
                        "API-key application review access",
                        Set.of(
                                ApiPermissionNames.API_KEY_VIEW,
                                ApiPermissionNames.API_KEY_APPROVE,
                                ApiPermissionNames.API_KEY_REVOKE,
                                ApiPermissionNames.API_USAGE_VIEW
                        ),
                        true,
                        BOOTSTRAP_TIME
                )
        );
    }

    private static List<UserRoleBindingRecord> seedBindings() {
        List<UserRoleBindingRecord> seeded = new ArrayList<>();
        seeded.add(new UserRoleBindingRecord("ops-admin", "OPS_ADMIN", "system", BOOTSTRAP_TIME));
        seeded.add(new UserRoleBindingRecord("ops-viewer", "OPS_VIEWER", "system", BOOTSTRAP_TIME));
        return seeded;
    }
}
