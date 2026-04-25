import {getAppConfig} from '@/app/config'

interface ApiResponseEnvelope<T> {
    code: number
    msg: string
    data: T
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
    init?: RequestInit,
): Promise<T> {
    const response = await fetch(`${getAppConfig().apiBaseUrl}${input}`, {
        ...init,
        headers: {
            'Content-Type': 'application/json',
            ...(init?.headers ?? {}),
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
    init?: RequestInit,
): Promise<T> {
    const payload = await requestJson<ApiResponseEnvelope<T>>(input, init)

    if (payload.code !== 0) {
        throw new ApiError(payload.msg, payload.code, payload)
    }

    return payload.data
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
