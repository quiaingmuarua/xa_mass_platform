package com.xa.mass.kernel.redis;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import org.junit.jupiter.api.Test;

class RedisKeyspaceTest {

    @Test
    void rendersFixedRootForValidProfileAndTestScopes() {
        assertEquals(
                "xa_mass:profile_default",
                new RedisKeyspace("profile_default").base()
        );
        assertEquals(
                "xa_mass:test_runtime_boundary_20260822_ab12cd34",
                new RedisKeyspace(
                        "test_runtime_boundary_20260822_ab12cd34"
                ).base()
        );
    }

    @Test
    void rejectsArbitraryOrUnsafeScopes() {
        for (String scope : new String[]{
                "default",
                "profile_",
                "test_",
                "Profile_default",
                "profile_scenario-workers",
                "profile:default",
                "test_runtime_*",
                "test_{runtime}",
                " test_runtime"
        }) {
            assertThrows(
                    IllegalArgumentException.class,
                    () -> new RedisKeyspace(scope),
                    scope
            );
        }
    }
}
