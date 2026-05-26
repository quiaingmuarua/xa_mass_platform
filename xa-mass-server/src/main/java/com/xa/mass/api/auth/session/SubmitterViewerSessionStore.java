package com.xa.mass.api.auth.session;

public interface SubmitterViewerSessionStore {

    SubmitterViewerSessionRecord create(SubmitterViewerSessionRecord record);

    SubmitterViewerSessionRecord get(String sessionId);

    SubmitterViewerSessionRecord getByCredentialHash(String credentialHash);

    SubmitterViewerSessionRecord revoke(String sessionId);
}
