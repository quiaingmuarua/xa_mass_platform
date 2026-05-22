import {resetRuntimeConfigOverrides, setRuntimeConfigOverrides} from '@/app/config'
import {getCurrentSubmitter} from '@/api/current-submitter'
import {getCurrentSubmitterReal} from '@/api/current-submitter.real'

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('current submitter API facade', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('returns unavailable in mock mode', async () => {
        setRuntimeConfigOverrides({ useMockApi: true })

        await expect(getCurrentSubmitter()).resolves.toEqual({
            state: 'unavailable',
            profile: null,
        })
    })
})

describe('current-submitter.real', () => {
    afterEach(() => {
        vi.unstubAllGlobals()
    })

    it('returns the authenticated submitter when introspection succeeds', async () => {
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

        await expect(getCurrentSubmitterReal()).resolves.toEqual({
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

    it('returns unauthorized when submitter credential is not present', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn(() =>
                Promise.resolve(
                    jsonResponse(
                        {
                            code: 401,
                            msg: 'Invalid or missing submitter credential',
                            data: null,
                        },
                        401,
                    ),
                ),
            ),
        )

        await expect(getCurrentSubmitterReal()).resolves.toEqual({
            state: 'unauthorized',
            profile: null,
        })
    })

    it('returns unavailable when submitter introspection endpoint is not exposed', async () => {
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

        await expect(getCurrentSubmitterReal()).resolves.toEqual({
            state: 'unavailable',
            profile: null,
        })
    })
})
