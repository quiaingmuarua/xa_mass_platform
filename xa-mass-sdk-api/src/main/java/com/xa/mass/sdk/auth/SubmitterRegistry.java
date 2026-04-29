package com.xa.mass.sdk.auth;

import java.util.List;

/**
 * Unified principal credential registry used by SDK submitter operations and
 * server-side credential authentication.
 */
public interface SubmitterRegistry extends AuthProvider, PrincipalDirectory {

    void register(SubmitterRegistration submitterRegistration);

    List<SubmitterMetadata> listSubmitters();

    SubmitterMetadata getSubmitter(String principalId);
}
