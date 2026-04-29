package com.xa.mass.api.auth;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalDirectory;
import com.xa.mass.sdk.auth.PrincipalType;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
public class DefaultOperatorPrincipalDirectory implements PrincipalDirectory {

    private static final String ATTR_DISPLAY_NAME = "displayName";
    private static final String ATTR_EMAIL = "email";
    private static final String ATTR_ROLES = "roles";

    private final Map<String, PrincipalContext> principalsById;

    public DefaultOperatorPrincipalDirectory() {
        Map<String, PrincipalContext> principals = new LinkedHashMap<>();
        PrincipalContext admin = PrincipalContext.builder()
                .principalId("ops-admin")
                .principalType(PrincipalType.OPERATOR)
                .userId("ops-admin")
                .permissions(ApiPermissionNames.ALL)
                .attributes(Map.of(
                        ATTR_DISPLAY_NAME, "Ops Admin",
                        ATTR_EMAIL, "ops-admin@example.internal",
                        ATTR_ROLES, "OPS_ADMIN"
                ))
                .projectScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .eventScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .build();
        PrincipalContext viewer = PrincipalContext.builder()
                .principalId("ops-viewer")
                .principalType(PrincipalType.OPERATOR)
                .userId("ops-viewer")
                .permissions(List.of(
                        ApiPermissionNames.TASK_VIEW,
                        ApiPermissionNames.WORKER_VIEW,
                        ApiPermissionNames.RULE_VIEW,
                        ApiPermissionNames.CONFIG_VIEW,
                        ApiPermissionNames.AUDIT_VIEW
                ))
                .attributes(Map.of(
                        ATTR_DISPLAY_NAME, "Ops Viewer",
                        ATTR_EMAIL, "ops-viewer@example.internal",
                        ATTR_ROLES, "OPS_VIEWER"
                ))
                .projectScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .eventScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .build();
        principals.put(admin.getPrincipalId(), admin);
        principals.put(viewer.getPrincipalId(), viewer);
        this.principalsById = Map.copyOf(principals);
    }

    @Override
    public PrincipalContext getPrincipal(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            return null;
        }
        return principalsById.get(principalId.trim());
    }

    public PrincipalContext requirePrincipal(String principalId) {
        PrincipalContext principal = getPrincipal(principalId);
        if (principal == null) {
            throw new IllegalArgumentException("Unknown operator principal: " + principalId);
        }
        return principal;
    }
}
