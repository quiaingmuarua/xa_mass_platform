package com.xa.mass.api.auth;

import com.xa.mass.api.auth.iam.InMemoryUserRolePermissionStore;
import com.xa.mass.sdk.auth.PrincipalContext;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class DefaultOperatorPrincipalDirectoryTest {

    @Test
    void resolvesBuiltInUsersFromUserRolePermissionStore() {
        DefaultOperatorPrincipalDirectory directory = new DefaultOperatorPrincipalDirectory(
                InMemoryUserRolePermissionStore.bootstrapDefaults());

        PrincipalContext admin = directory.getPrincipal("ops-admin");
        PrincipalContext viewer = directory.getPrincipal("ops-viewer");

        assertNotNull(admin);
        assertEquals("ops-admin", admin.getUserId());
        assertTrue(admin.hasPermission(ApiPermissionNames.API_KEY_APPROVE));
        assertEquals("OPS_ADMIN", admin.getAttributes().get("roles"));

        assertNotNull(viewer);
        assertEquals("ops-viewer", viewer.getUserId());
        assertTrue(viewer.hasPermission(ApiPermissionNames.TASK_VIEW));
        assertEquals("OPS_VIEWER", viewer.getAttributes().get("roles"));
    }

    @Test
    void unknownUserReturnsNull() {
        DefaultOperatorPrincipalDirectory directory = new DefaultOperatorPrincipalDirectory(
                InMemoryUserRolePermissionStore.bootstrapDefaults());

        assertNull(directory.getPrincipal("missing-user"));
    }
}
