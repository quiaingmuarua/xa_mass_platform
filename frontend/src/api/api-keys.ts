import {getAppConfig} from '@/app/config'
import {requestApiData} from '@/api/http'
import {
    approveApiKeyApplicationMock,
    createApiKeyApplicationMock,
    createApiKeyMock,
    getApiKeyApplicationMock,
    getApiKeyMock,
    listApiKeyApplicationsMock,
    listApiKeysMock,
    listApiKeyUsageMock,
    rejectApiKeyApplicationMock,
    revokeApiKeyMock,
} from '@/api/api-keys.mock'
import type {
    ApiKeyApplicationCreateRequest,
    ApiKeyApplicationRecord,
    ApiUsageLedgerRecord,
    ApiKeyCreateRequest,
    ApiKeyCreateResponse,
    ApiKeyCredentialView,
} from '@/types/api-keys'

export async function listApiKeys(): Promise<ApiKeyCredentialView[]> {
    if (getAppConfig().useMockApi) {
        return listApiKeysMock()
    }
    return requestApiData<ApiKeyCredentialView[]>('/api/v1/api-keys')
}

export async function getApiKey(
    keyId: string,
): Promise<ApiKeyCredentialView> {
    if (getAppConfig().useMockApi) {
        return getApiKeyMock(keyId)
    }
    return requestApiData<ApiKeyCredentialView>(
        `/api/v1/api-keys/${encodeURIComponent(keyId)}`,
    )
}

export async function createApiKey(
    request: ApiKeyCreateRequest,
): Promise<ApiKeyCreateResponse> {
    if (getAppConfig().useMockApi) {
        return createApiKeyMock(request)
    }
    return requestApiData<ApiKeyCreateResponse>('/api/v1/api-keys', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function revokeApiKey(
    keyId: string,
    reason: string,
): Promise<ApiKeyCredentialView> {
    if (getAppConfig().useMockApi) {
        return revokeApiKeyMock(keyId, reason)
    }
    return requestApiData<ApiKeyCredentialView>(
        `/api/v1/api-keys/${encodeURIComponent(keyId)}:revoke`,
        {
            method: 'POST',
            body: JSON.stringify({
                reason: reason.trim() || undefined,
            }),
        },
    )
}

export async function listApiKeyApplications(): Promise<
    ApiKeyApplicationRecord[]
> {
    if (getAppConfig().useMockApi) {
        return listApiKeyApplicationsMock()
    }
    return requestApiData<ApiKeyApplicationRecord[]>(
        '/api/v1/api-key-applications',
    )
}

export async function getApiKeyApplication(
    applicationId: string,
): Promise<ApiKeyApplicationRecord> {
    if (getAppConfig().useMockApi) {
        return getApiKeyApplicationMock(applicationId)
    }
    return requestApiData<ApiKeyApplicationRecord>(
        `/api/v1/api-key-applications/${encodeURIComponent(applicationId)}`,
    )
}

export async function createApiKeyApplication(
    request: ApiKeyApplicationCreateRequest,
): Promise<ApiKeyApplicationRecord> {
    if (getAppConfig().useMockApi) {
        return createApiKeyApplicationMock(request)
    }
    return requestApiData<ApiKeyApplicationRecord>(
        '/api/v1/api-key-applications',
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )
}

export async function approveApiKeyApplication(
    applicationId: string,
    reason: string,
): Promise<ApiKeyCreateResponse> {
    if (getAppConfig().useMockApi) {
        return approveApiKeyApplicationMock(applicationId, reason)
    }
    return requestApiData<ApiKeyCreateResponse>(
        `/api/v1/api-key-applications/${encodeURIComponent(applicationId)}:approve`,
        {
            method: 'POST',
            body: JSON.stringify({
                reason: reason.trim() || undefined,
            }),
        },
    )
}

export async function rejectApiKeyApplication(
    applicationId: string,
    reason: string,
): Promise<ApiKeyApplicationRecord> {
    if (getAppConfig().useMockApi) {
        return rejectApiKeyApplicationMock(applicationId, reason)
    }
    return requestApiData<ApiKeyApplicationRecord>(
        `/api/v1/api-key-applications/${encodeURIComponent(applicationId)}:reject`,
        {
            method: 'POST',
            body: JSON.stringify({
                reason: reason.trim() || undefined,
            }),
        },
    )
}

export async function listApiKeyUsage(
    keyId: string,
): Promise<{ items: ApiUsageLedgerRecord[]; total: number }> {
    if (getAppConfig().useMockApi) {
        return listApiKeyUsageMock(keyId)
    }
    return requestApiData<{ items: ApiUsageLedgerRecord[]; total: number }>(
        `/api/v1/api-keys/${encodeURIComponent(keyId)}/usage`,
    )
}
