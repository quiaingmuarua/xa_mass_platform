import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import {getCurrentApiKey} from '@/api/current-api-key'
import {getCurrentApiKeyReal} from '@/api/current-api-key.real'

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('current API-key API facade', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('returns unavailable in mock mode', async () => {
        setRuntimeConfigOverrides({ useMockApi: true })

        await expect(getCurrentApiKey()).resolves.toEqual({
            state: 'unavailable',
            profile: null,
        })
    })
})

describe('current-api-key.real', () => {
    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('returns the authenticated API key when introspection succeeds', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn(() =>
                Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            principalId: 'crawler-agent',
                            userId: 'crawler-user',
                            projectScope: 'crawlerApp',
                            attributes: {
                                transport: 'polling',
                            },
                        },
                    }),
                ),
            ),
        )

        await expect(getCurrentApiKeyReal()).resolves.toEqual({
            state: 'available',
            profile: {
                principalId: 'crawler-agent',
                userId: 'crawler-user',
                projectScope: 'crawlerApp',
                attributes: {
                    transport: 'polling',
                },
            },
        })
    })

    it('returns unauthorized when API-key credential is not present', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn(() =>
                Promise.resolve(
                    jsonResponse(
                        {
                            code: 401,
                            msg: 'Invalid or missing API-key credential',
                            data: null,
                        },
                        401,
                    ),
                ),
            ),
        )

        await expect(getCurrentApiKeyReal()).resolves.toEqual({
            state: 'unauthorized',
            profile: null,
        })
    })

    it('returns unavailable when API-key introspection endpoint is not exposed', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn(() =>
                Promise.resolve(
                    jsonResponse(
                        {
                            code: 404,
                            msg: 'Not found',
                            data: null,
                        },
                        404,
                    ),
                ),
            ),
        )

        await expect(getCurrentApiKeyReal()).resolves.toEqual({
            state: 'unavailable',
            profile: null,
        })
    })
})
