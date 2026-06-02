package com.xa.mass.sdk.auth;

import java.util.List;

/**
 * Unified principal credential registry used by submitter resource operations and
 * server-side credential authentication.
 */
public interface SubmitterRegistry extends AuthProvider, PrincipalDirectory {

    void register(SubmitterRegistration submitterRegistration);

    List<SubmitterProfile> listSubmitters();

    SubmitterProfile getSubmitter(String principalId);
}
