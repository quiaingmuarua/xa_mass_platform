import {requestApiData} from '@/api/http'
import type {
    CurrentApiKeyUsageResponse,
    ApiKeyViewerSessionCreateResponse,
    ApiKeyViewerSessionView,
} from '@/types/api-keys'
import type {CurrentApiKeyProfile} from '@/types/current-api-key'

export async function createApiKeyViewerSession(
    sourceApiKey: string,
): Promise<ApiKeyViewerSessionCreateResponse> {
    return requestApiData<ApiKeyViewerSessionCreateResponse>(
        '/api/v1/api-key-viewer-sessions',
        {
            method: 'POST',
            apiKeyCredential: sourceApiKey,
            includeOperatorAuth: false,
        },
    )
}

export async function getCurrentApiKeyViewerSession(
    sessionCredential: string,
): Promise<ApiKeyViewerSessionView> {
    return requestApiData<ApiKeyViewerSessionView>(
        '/api/v1/api-key-viewer-sessions/me',
        {
            apiKeyCredential: sessionCredential,
            includeOperatorAuth: false,
        },
    )
}

export async function logoutApiKeyViewerSession(
    sessionCredential: string,
): Promise<ApiKeyViewerSessionView> {
    return requestApiData<ApiKeyViewerSessionView>(
        '/api/v1/api-key-viewer-sessions:logout',
        {
            method: 'POST',
            apiKeyCredential: sessionCredential,
            includeOperatorAuth: false,
        },
    )
}

export async function getApiKeyProfileWithCredential(
    credential: string,
): Promise<CurrentApiKeyProfile> {
    return requestApiData<CurrentApiKeyProfile>('/api/v1/api-keys:current', {
        apiKeyCredential: credential,
        includeOperatorAuth: false,
    })
}

export async function getApiKeyUsageWithCredential(
    credential: string,
): Promise<CurrentApiKeyUsageResponse> {
    return requestApiData<CurrentApiKeyUsageResponse>(
        '/api/v1/api-keys:current/usage',
        {
            apiKeyCredential: credential,
            includeOperatorAuth: false,
        },
    )
}
