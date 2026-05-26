package com.xa.mass.api.auth.apikey;

import java.util.List;

public interface ApiKeyApplicationStore {

    ApiKeyApplicationRecord create(ApiKeyApplicationRecord record);

    ApiKeyApplicationRecord get(String applicationId);

    List<ApiKeyApplicationRecord> list();

    ApiKeyApplicationRecord markApproved(String applicationId, String reviewedBy, String reviewReason);

    ApiKeyApplicationRecord markRejected(String applicationId, String reviewedBy, String reviewReason);
}
