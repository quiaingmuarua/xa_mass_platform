package com.xa.mass.sdk.auth;

import java.util.List;

/**
 * Submitter credential resource registry.
 *
 * <p>Authentication, auth projection writes, and principal lookup are separate
 * owner contracts. A backend may implement those contracts alongside this
 * resource registry, but callers should depend on the narrow contract they
 * actually need.
 */
@Deprecated(forRemoval = true)
public interface SubmitterRegistry {

    void register(SubmitterRegistration submitterRegistration);

    List<SubmitterProfile> listSubmitters();

    SubmitterProfile getSubmitter(String principalId);
}
