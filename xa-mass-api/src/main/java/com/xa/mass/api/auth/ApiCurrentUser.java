package com.xa.mass.api.auth;

import java.util.List;

public record ApiCurrentUser(
        String id,
        String name,
        String email,
        List<String> roles,
        List<String> permissions
) {
}
