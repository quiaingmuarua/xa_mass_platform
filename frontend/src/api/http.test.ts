import { setRuntimeConfigOverrides } from '@/app/config'
import { ApiError, requestApiData, requestJson } from '@/api/http'
import { setBackendAuthConfig, setOperatorCsrfToken } from '@/auth/backend-auth'
import { useOperatorMode } from '@/auth/operator-mode'

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
            message: 'Task create failed: Unsupported event code: demo.missing',
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

    it('rejects successful responses that are not API envelopes', async () => {
        vi.stubGlobal('fetch', vi.fn().mockResolvedValue(jsonResponse(null)))

        await expect(requestApiData('/api/v1/auth/me')).rejects.toMatchObject({
            message: 'Invalid API response envelope',
            status: 500,
        } satisfies Partial<ApiError>)
    })

    it('adds the selected backend operator mode header outside mock auth', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        setBackendAuthConfig({
            authMode: 'dev-header',
            operatorHeaderSupported: true,
            sessionCookieSupported: false,
        })
        const { setOperatorMode } = useOperatorMode()
        setOperatorMode('viewer')
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: [],
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        await requestApiData('/api/v1/users')

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/users',
            expect.objectContaining({
                headers: expect.objectContaining({
                    'X-Mass-User-Mode': 'viewer',
                }),
            }),
        )
    })

    it('uses session credentials and CSRF without dev-header identity headers', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        setBackendAuthConfig({
            authMode: 'session',
            operatorHeaderSupported: false,
            sessionCookieSupported: true,
            csrfHeaderName: 'X-Mass-Csrf-Token',
        })
        setOperatorCsrfToken('csrf-token-1')
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {},
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        await requestApiData('/api/v1/users', {
            method: 'POST',
            body: JSON.stringify({ userId: 'ops-user' }),
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/users',
            expect.objectContaining({
                method: 'POST',
                credentials: 'same-origin',
                headers: expect.objectContaining({
                    'X-Mass-Csrf-Token': 'csrf-token-1',
                }),
            }),
        )
        expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty(
            'X-Mass-User-Mode',
        )
    })

    it('can send an explicit submitter credential without operator auth', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        setBackendAuthConfig({
            authMode: 'session',
            operatorHeaderSupported: false,
            sessionCookieSupported: true,
            csrfHeaderName: 'X-Mass-Csrf-Token',
        })
        setOperatorCsrfToken('csrf-token-1')
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {},
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        await requestApiData('/api/v1/api-keys:current', {
            submitterCredential: 'mass_sess_secret',
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/api-keys:current',
            expect.objectContaining({
                credentials: 'omit',
                headers: expect.objectContaining({
                    'X-Mass-Api-Key': 'mass_sess_secret',
                }),
            }),
        )
        expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty(
            'X-Mass-User-Mode',
        )
        expect(fetchMock.mock.calls[0][1].headers).not.toHaveProperty(
            'X-Mass-Csrf-Token',
        )
    })
})
