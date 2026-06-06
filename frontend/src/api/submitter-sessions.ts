import {requestApiData} from '@/api/http'
import type {
    CurrentSubmitterUsageResponse,
    SubmitterViewerSessionCreateResponse,
    SubmitterViewerSessionView,
} from '@/types/api-keys'
import type {CurrentSubmitterProfile} from '@/types/current-submitter'

export async function createSubmitterViewerSession(
    sourceApiKey: string,
): Promise<SubmitterViewerSessionCreateResponse> {
    return requestApiData<SubmitterViewerSessionCreateResponse>(
        '/api/v1/api-key-viewer-sessions',
        {
            method: 'POST',
            submitterCredential: sourceApiKey,
            includeOperatorAuth: false,
        },
    )
}

export async function getCurrentSubmitterViewerSession(
    sessionCredential: string,
): Promise<SubmitterViewerSessionView> {
    return requestApiData<SubmitterViewerSessionView>(
        '/api/v1/api-key-viewer-sessions/me',
        {
            submitterCredential: sessionCredential,
            includeOperatorAuth: false,
        },
    )
}

export async function logoutSubmitterViewerSession(
    sessionCredential: string,
): Promise<SubmitterViewerSessionView> {
    return requestApiData<SubmitterViewerSessionView>(
        '/api/v1/api-key-viewer-sessions:logout',
        {
            method: 'POST',
            submitterCredential: sessionCredential,
            includeOperatorAuth: false,
        },
    )
}

export async function getSubmitterProfileWithCredential(
    credential: string,
): Promise<CurrentSubmitterProfile> {
    return requestApiData<CurrentSubmitterProfile>('/api/v1/api-keys:current', {
        submitterCredential: credential,
        includeOperatorAuth: false,
    })
}

export async function getSubmitterUsageWithCredential(
    credential: string,
): Promise<CurrentSubmitterUsageResponse> {
    return requestApiData<CurrentSubmitterUsageResponse>(
        '/api/v1/api-keys:current/usage',
        {
            submitterCredential: credential,
            includeOperatorAuth: false,
        },
    )
}
