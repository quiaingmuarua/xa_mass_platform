package com.xa.mass.api.auth.apikey;

import java.util.List;

public interface ApiKeyCredentialStore {

    ApiKeyCredentialRecord create(ApiKeyCredentialRecord record);

    ApiKeyCredentialRecord get(String keyId);

    ApiKeyCredentialRecord getByPrincipalId(String principalId);

    List<ApiKeyCredentialRecord> list();

    ApiKeyCredentialRecord revoke(String keyId, String revokedBy, String revokeReason);

    List<ApiKeyCredentialRecord> disableByUserId(String userId, String disabledBy, String disableReason);

    ApiKeyCredentialRecord expire(String keyId);
}
