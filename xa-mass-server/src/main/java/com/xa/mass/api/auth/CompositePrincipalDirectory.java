package com.xa.mass.api.auth;

import com.xa.mass.sdk.auth.PrincipalContext;
import com.xa.mass.sdk.auth.PrincipalDirectory;

import java.util.List;
import java.util.Objects;

/**
 * Server-side principal directory that merges built-in operator principals with
 * SDK-backed credential principals without creating a second auth truth model.
 */
public final class CompositePrincipalDirectory implements PrincipalDirectory {

    private final List<PrincipalDirectory> delegates;

    public CompositePrincipalDirectory(List<PrincipalDirectory> delegates) {
        this.delegates = List.copyOf(Objects.requireNonNull(delegates, "delegates"));
    }

    @Override
    public PrincipalContext getPrincipal(String principalId) {
        if (principalId == null || principalId.isBlank()) {
            return null;
        }
        for (PrincipalDirectory delegate : delegates) {
            if (delegate == null) {
                continue;
            }
            PrincipalContext principal = delegate.getPrincipal(principalId);
            if (principal != null) {
                return principal;
            }
        }
        return null;
    }
}
