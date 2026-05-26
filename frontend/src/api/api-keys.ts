import {requestApiData} from '@/api/http'
import type {
    ApiKeyApplicationCreateRequest,
    ApiKeyApplicationRecord,
    ApiKeyCreateRequest,
    ApiKeyCreateResponse,
    ApiKeyCredentialView,
} from '@/types/api-keys'

export async function listApiKeys(): Promise<ApiKeyCredentialView[]> {
    return requestApiData<ApiKeyCredentialView[]>('/api/v1/api-keys')
}

export async function getApiKey(
    keyId: string,
): Promise<ApiKeyCredentialView> {
    return requestApiData<ApiKeyCredentialView>(
        `/api/v1/api-keys/${encodeURIComponent(keyId)}`,
    )
}

export async function createApiKey(
    request: ApiKeyCreateRequest,
): Promise<ApiKeyCreateResponse> {
    return requestApiData<ApiKeyCreateResponse>('/api/v1/api-keys', {
        method: 'POST',
        body: JSON.stringify(request),
    })
}

export async function revokeApiKey(
    keyId: string,
    reason: string,
): Promise<ApiKeyCredentialView> {
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
    return requestApiData<ApiKeyApplicationRecord[]>(
        '/api/v1/api-key-applications',
    )
}

export async function getApiKeyApplication(
    applicationId: string,
): Promise<ApiKeyApplicationRecord> {
    return requestApiData<ApiKeyApplicationRecord>(
        `/api/v1/api-key-applications/${encodeURIComponent(applicationId)}`,
    )
}

export async function createApiKeyApplication(
    request: ApiKeyApplicationCreateRequest,
): Promise<ApiKeyApplicationRecord> {
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
