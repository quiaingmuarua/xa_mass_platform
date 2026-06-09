import { getAppConfig } from '@/app/config'
import {
    currentBackendAuthConfig,
    currentOperatorCsrfHeader,
} from '@/auth/backend-auth'
import { currentOperatorModeHeader } from '@/auth/operator-mode'

interface ApiResponseEnvelope<T> {
    code: number
    msg: string
    data: T
}

export interface ApiRequestInit extends RequestInit {
    apiKeyCredential?: string
    includeOperatorAuth?: boolean
}

export class ApiError extends Error {
    public readonly status: number
    public readonly payload: unknown

    constructor(message: string, status: number, payload: unknown) {
        super(message)
        this.status = status
        this.payload = payload
    }
}

const inFlightGetRequests = new Map<string, Promise<unknown>>()

export async function requestJson<T>(
    input: string,
    init?: ApiRequestInit,
): Promise<T> {
    const {
        apiKeyCredential,
        includeOperatorAuth = true,
        headers,
        ...fetchInit
    } = init ?? {}
    const attachOperatorSession = shouldAttachOperatorSession(
        includeOperatorAuth,
        apiKeyCredential,
    )
    const requestUrl = buildApiUrl(input)
    const normalizedMethod = normalizeMethod(fetchInit.method)
    const requestHeaders = {
        'Content-Type': 'application/json',
        ...operatorModeHeader(includeOperatorAuth, apiKeyCredential),
        ...csrfHeader(
            fetchInit.method,
            includeOperatorAuth,
            apiKeyCredential,
        ),
        ...apiKeyCredentialHeader(apiKeyCredential),
        ...(headers ?? {}),
    }
    const requestOptions = {
        ...fetchInit,
        method: normalizedMethod,
        credentials:
            fetchInit.credentials ??
            (attachOperatorSession ? 'same-origin' : 'omit'),
        headers: requestHeaders,
    }
    const dedupeKey = getDedupeKey(requestUrl, requestOptions)
    if (dedupeKey) {
        const existingRequest = inFlightGetRequests.get(dedupeKey)
        if (existingRequest) {
            return existingRequest as Promise<T>
        }
    }

    const requestPromise = sendJsonRequest<T>(requestUrl, requestOptions)
    if (!dedupeKey) {
        return requestPromise
    }
    inFlightGetRequests.set(dedupeKey, requestPromise)
    try {
        return await requestPromise
    } finally {
        inFlightGetRequests.delete(dedupeKey)
    }
}

async function sendJsonRequest<T>(
    requestUrl: string,
    requestOptions: RequestInit,
): Promise<T> {
    const response = await fetch(requestUrl, requestOptions)
    const payload = await response.json().catch(() => null)

    if (!response.ok) {
        throw new ApiError(
            extractErrorMessage(payload, response.status),
            response.status,
            payload,
        )
    }

    return payload as T
}

export async function requestApiData<T>(
    input: string,
    init?: ApiRequestInit,
): Promise<T> {
    const payload = await requestJson<ApiResponseEnvelope<T>>(input, init)

    if (!isApiResponseEnvelope<T>(payload)) {
        throw new ApiError('Invalid API response envelope', 500, payload)
    }

    if (payload.code !== 0) {
        throw new ApiError(payload.msg, payload.code, payload)
    }

    return payload.data
}

function operatorModeHeader(
    includeOperatorAuth: boolean,
    apiKeyCredential: string | undefined,
): Record<string, string> {
    if (
        getAppConfig().useMockAuth ||
        !includeOperatorAuth ||
        hasApiKeyCredential(apiKeyCredential) ||
        !currentBackendAuthConfig().operatorHeaderSupported
    ) {
        return {}
    }

    return {
        'X-Mass-User-Mode': currentOperatorModeHeader(),
    }
}

function apiKeyCredentialHeader(
    credential: string | undefined,
): Record<string, string> {
    const normalized = credential?.trim()
    if (!normalized) {
        return {}
    }

    return {
        'X-Mass-Api-Key': normalized,
    }
}

function csrfHeader(
    method: string | undefined,
    includeOperatorAuth: boolean,
    apiKeyCredential: string | undefined,
): Record<string, string> {
    if (
        !shouldAttachOperatorSession(
            includeOperatorAuth,
            apiKeyCredential,
        ) ||
        isSafeMethod(method)
    ) {
        return {}
    }
    return currentOperatorCsrfHeader()
}

function shouldAttachOperatorSession(
    includeOperatorAuth: boolean,
    apiKeyCredential: string | undefined,
): boolean {
    return (
        !getAppConfig().useMockAuth &&
        includeOperatorAuth &&
        !hasApiKeyCredential(apiKeyCredential) &&
        currentBackendAuthConfig().sessionCookieSupported
    )
}

function hasApiKeyCredential(credential: string | undefined): boolean {
    return credential !== undefined && credential.trim().length > 0
}

function isSafeMethod(method: string | undefined): boolean {
    const normalized = normalizeMethod(method)
    return (
        normalized === 'GET' ||
        normalized === 'HEAD' ||
        normalized === 'OPTIONS'
    )
}

function normalizeMethod(method: string | undefined): string {
    return method?.trim().toUpperCase() || 'GET'
}

function getDedupeKey(
    requestUrl: string,
    requestOptions: RequestInit,
): string | null {
    if (normalizeMethod(requestOptions.method) !== 'GET') {
        return null
    }
    if (requestOptions.body !== undefined) {
        return null
    }
    return JSON.stringify({
        url: requestUrl,
        credentials: requestOptions.credentials ?? null,
        headers: requestOptions.headers ?? {},
    })
}

function isApiResponseEnvelope<T>(
    payload: ApiResponseEnvelope<T> | unknown,
): payload is ApiResponseEnvelope<T> {
    if (!payload || typeof payload !== 'object') {
        return false
    }

    const record = payload as Record<string, unknown>
    return typeof record.code === 'number' && typeof record.msg === 'string'
}

export function buildApiUrl(input: string): string {
    return `${getAppConfig().apiBaseUrl}${input}`
}

export function triggerDownload(url: string): void {
    const anchor = document.createElement('a')
    anchor.href = url
    anchor.download = ''
    anchor.rel = 'noopener'
    document.body.appendChild(anchor)
    anchor.click()
    document.body.removeChild(anchor)
}

function extractErrorMessage(payload: unknown, status: number): string {
    if (payload && typeof payload === 'object') {
        const record = payload as Record<string, unknown>
        const envelopeMessage = record.msg
        if (
            typeof envelopeMessage === 'string' &&
            envelopeMessage.trim().length > 0
        ) {
            return envelopeMessage
        }

        const plainMessage = record.message
        if (
            typeof plainMessage === 'string' &&
            plainMessage.trim().length > 0
        ) {
            return plainMessage
        }
    }

    return `Request failed: ${status}`
}
