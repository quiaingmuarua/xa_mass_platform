import {
    createApiKeyViewerSession,
    getCurrentApiKeyViewerSession,
    getApiKeyProfileWithCredential,
    getApiKeyUsageWithCredential,
    logoutApiKeyViewerSession,
} from '@/api/api-key-viewer-sessions'
import {setRuntimeConfigOverrides} from '@/app/config'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('API-key viewer session API client', () => {
    it('uses explicit API-key credentials without operator auth headers', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        const fetchMock = vi.fn().mockImplementation(() =>
            Promise.resolve(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        session: {
                            sessionId: 'svs-1',
                            keyId: 'ak-1',
                            principalId: 'crawler-key',
                            createdForUserId: 'ops-admin',
                            keyPrefix: 'mass_sess_123...',
                            permissions: ['task:view'],
                            projectScopes: ['crawlerApp'],
                            eventScopes: [],
                            attributes: {},
                            createdAt: '2026-05-26T00:00:00Z',
                            expiresAt: '2026-05-26T08:00:00Z',
                            revokedAt: null,
                        },
                        rawSecret: 'mass_sess_secret',
                    },
                }),
            ),
        )
        vi.stubGlobal('fetch', fetchMock)

        await createApiKeyViewerSession('mass_sk_source')
        await getCurrentApiKeyViewerSession('mass_sess_secret')
        await getApiKeyProfileWithCredential('mass_sess_secret')
        await getApiKeyUsageWithCredential('mass_sess_secret')
        await logoutApiKeyViewerSession('mass_sess_secret')

        expect(fetchMock.mock.calls.map(([input]) => input)).toEqual([
            '/backend/api/v1/api-key-viewer-sessions',
            '/backend/api/v1/api-key-viewer-sessions/me',
            '/backend/api/v1/api-keys:current',
            '/backend/api/v1/api-keys:current/usage',
            '/backend/api/v1/api-key-viewer-sessions:logout',
        ])
        for (const [, init] of fetchMock.mock.calls) {
            expect(init.headers).toMatchObject({
                'X-Mass-Api-Key': expect.stringMatching(/^mass_/),
            })
            expect(init.headers).not.toHaveProperty('X-Mass-User-Mode')
        }
    })
})
