import {getAppConfig} from '@/app/config'
import {currentOperatorModeHeader} from '@/auth/operator-mode'

interface ApiResponseEnvelope<T> {
    code: number
    msg: string
    data: T
}

export interface ApiRequestInit extends RequestInit {
    submitterCredential?: string
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

export async function requestJson<T>(
    input: string,
    init?: ApiRequestInit,
): Promise<T> {
    const {
        submitterCredential,
        includeOperatorAuth = true,
        headers,
        ...fetchInit
    } = init ?? {}
    const response = await fetch(buildApiUrl(input), {
        ...fetchInit,
        headers: {
            'Content-Type': 'application/json',
            ...(includeOperatorAuth ? operatorModeHeader() : {}),
            ...submitterCredentialHeader(submitterCredential),
            ...(headers ?? {}),
        },
    })

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

function operatorModeHeader(): Record<string, string> {
    if (getAppConfig().useMockAuth) {
        return {}
    }

    return {
        'X-Mass-User-Mode': currentOperatorModeHeader(),
    }
}

function submitterCredentialHeader(
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
