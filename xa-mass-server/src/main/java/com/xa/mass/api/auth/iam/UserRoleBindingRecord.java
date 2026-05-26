package com.xa.mass.api.auth.iam;

import java.time.Instant;

public record UserRoleBindingRecord(
        String userId,
        String roleId,
        String grantedBy,
        Instant grantedAt
) {
}
