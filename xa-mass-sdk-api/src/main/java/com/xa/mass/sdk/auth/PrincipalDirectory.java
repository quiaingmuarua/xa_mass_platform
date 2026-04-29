package com.xa.mass.sdk.auth;

/**
 * Low-frequency principal lookup seam for control-plane callers that need a
 * unified principal view without authenticating by credential on every path.
 */
public interface PrincipalDirectory {

    PrincipalContext getPrincipal(String principalId);
}
