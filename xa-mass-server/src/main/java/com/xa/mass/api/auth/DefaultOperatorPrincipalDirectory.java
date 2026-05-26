package com.xa.mass.api.auth;

import com.xa.mass.api.auth.iam.RoleRecord;
import com.xa.mass.api.auth.iam.UserRecord;
import com.xa.mass.api.auth.iam.UserRoleBindingRecord;
import com.xa.mass.api.auth.iam.UserRolePermissionStore;
import com.xa.mass.api.auth.iam.UserStatus;
import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalDirectory;
import com.xa.mass.sdk.auth.PrincipalType;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

@Component
public class DefaultOperatorPrincipalDirectory implements PrincipalDirectory {

    private static final String ATTR_DISPLAY_NAME = "displayName";
    private static final String ATTR_EMAIL = "email";
    private static final String ATTR_ROLES = "roles";

    private final UserRolePermissionStore store;

    public DefaultOperatorPrincipalDirectory() {
        this(InMemoryUserRolePermissionStore.bootstrapDefaults());
    }

    @Autowired
    public DefaultOperatorPrincipalDirectory(UserRolePermissionStore store) {
        this.store = Objects.requireNonNull(store, "store");
    }

    @Override
    public PrincipalContext getPrincipal(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            return null;
        }
        UserRecord user = store.getUser(principalId.trim());
        if (user == null || user.status() != UserStatus.ACTIVE) {
            return null;
        }
        List<UserRoleBindingRecord> bindings = store.listRoleBindings(user.userId());
        Set<String> permissions = new LinkedHashSet<>();
        List<String> roleIds = bindings.stream()
                .map(UserRoleBindingRecord::roleId)
                .filter(roleId -> roleId != null && !roleId.isBlank())
                .distinct()
                .toList();
        for (String roleId : roleIds) {
            RoleRecord role = store.getRole(roleId);
            if (role != null) {
                permissions.addAll(role.permissions());
            }
        }
        return PrincipalContext.builder()
                .principalId(user.userId())
                .principalType(PrincipalType.OPERATOR)
                .userId(user.userId())
                .permissions(List.copyOf(permissions))
                .attributes(Map.of(
                        ATTR_DISPLAY_NAME, defaultIfBlank(user.displayName(), user.userId()),
                        ATTR_EMAIL, defaultIfBlank(user.email(), user.userId() + "@example.internal"),
                        ATTR_ROLES, roleIds.stream().collect(Collectors.joining(","))
                ))
                .projectScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .eventScopes(List.of(PrincipalContext.WILDCARD_SCOPE))
                .build();
    }

    public PrincipalContext requirePrincipal(String principalId) {
        PrincipalContext principal = getPrincipal(principalId);
        if (principal == null) {
            throw new IllegalArgumentException("Unknown operator principal: " + principalId);
        }
        return principal;
    }

    private String defaultIfBlank(String value, String defaultValue) {
        return value == null || value.isBlank() ? defaultValue : value;
    }
}
