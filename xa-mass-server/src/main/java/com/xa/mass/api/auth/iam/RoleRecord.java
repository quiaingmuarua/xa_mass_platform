package com.xa.mass.api.auth.iam;

import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.Set;

public record RoleRecord(
        String roleId,
        String name,
        String description,
        Set<String> permissions,
        boolean systemRole,
        Instant updatedAt
) {
    public RoleRecord {
        permissions = permissions == null ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(permissions));
    }
}
