package com.xa.mass.api.auth.session;

public interface ApiKeyViewerSessionStore {

    ApiKeyViewerSessionRecord create(ApiKeyViewerSessionRecord record);

    ApiKeyViewerSessionRecord get(String sessionId);

    ApiKeyViewerSessionRecord getByCredentialHash(String credentialHash);

    ApiKeyViewerSessionRecord revoke(String sessionId);
}
