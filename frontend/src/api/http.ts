import { appConfig } from '@/app/config'

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
    const response = await fetch(`${appConfig.apiBaseUrl}${input}`, {
        ...init,
        headers: {
            'Content-Type': 'application/json',
            ...(init?.headers ?? {}),
        },
    })

    const payload = await response.json().catch(() => null)

    if (!response.ok) {
        throw new ApiError(
            `Request failed: ${response.status}`,
            response.status,
            payload,
        )
    }

    return payload as T
}
