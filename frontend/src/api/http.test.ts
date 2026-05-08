import {setRuntimeConfigOverrides} from '@/app/config'
import {ApiError, requestApiData, requestJson} from '@/api/http'

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('http API helpers', () => {
    it('surfaces backend envelope msg for non-2xx responses', async () => {
        setRuntimeConfigOverrides({ apiBaseUrl: '/backend' })
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                jsonResponse(
                    {
                        code: 400,
                        msg: 'Task create failed: Unsupported event code: demo.missing',
                        data: null,
                    },
                    400,
                ),
            ),
        )

        await expect(requestJson('/api/v1/tasks')).rejects.toMatchObject({
            name: 'Error',
            message:
                'Task create failed: Unsupported event code: demo.missing',
            status: 400,
        } satisfies Partial<ApiError>)
    })

    it('falls back to plain message when msg is absent', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                jsonResponse(
                    {
                        message: 'Plain backend failure',
                    },
                    400,
                ),
            ),
        )

        await expect(requestJson('/api/v1/tasks')).rejects.toMatchObject({
            message: 'Plain backend failure',
            status: 400,
        } satisfies Partial<ApiError>)
    })

    it('keeps requestApiData envelope validation behavior', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                jsonResponse({
                    code: 403,
                    msg: 'Permission denied',
                    data: null,
                }),
            ),
        )

        await expect(requestApiData('/api/v1/auth/me')).rejects.toMatchObject({
            message: 'Permission denied',
            status: 403,
        } satisfies Partial<ApiError>)
    })
})
