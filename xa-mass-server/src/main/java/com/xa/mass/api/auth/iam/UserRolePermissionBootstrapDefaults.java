package com.xa.mass.api.auth.iam;

import com.xa.mass.api.auth.ApiPermissionNames;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class UserRolePermissionBootstrapDefaults {

    private static final Instant BOOTSTRAP_TIME = Instant.EPOCH;

    private UserRolePermissionBootstrapDefaults() {
    }

    public static List<UserRecord> users() {
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

    public static List<RoleRecord> roles() {
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

    public static List<UserRoleBindingRecord> bindings() {
        List<UserRoleBindingRecord> seeded = new ArrayList<>();
        seeded.add(new UserRoleBindingRecord("ops-admin", "OPS_ADMIN", "system", BOOTSTRAP_TIME));
        seeded.add(new UserRoleBindingRecord("ops-viewer", "OPS_VIEWER", "system", BOOTSTRAP_TIME));
        return seeded;
    }

    public static List<String> permissions() {
        return ApiPermissionNames.ALL;
    }
}
