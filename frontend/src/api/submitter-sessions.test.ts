import {
    createSubmitterViewerSession,
    getCurrentSubmitterViewerSession,
    getSubmitterProfileWithCredential,
    getSubmitterUsageWithCredential,
    logoutSubmitterViewerSession,
} from '@/api/submitter-sessions'
import {setRuntimeConfigOverrides} from '@/app/config'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('submitter viewer session API client', () => {
    it('uses explicit submitter credentials without operator auth headers', async () => {
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

        await createSubmitterViewerSession('mass_sk_source')
        await getCurrentSubmitterViewerSession('mass_sess_secret')
        await getSubmitterProfileWithCredential('mass_sess_secret')
        await getSubmitterUsageWithCredential('mass_sess_secret')
        await logoutSubmitterViewerSession('mass_sess_secret')

        expect(fetchMock.mock.calls.map(([input]) => input)).toEqual([
            '/backend/api/v1/submitter-sessions',
            '/backend/api/v1/submitter-sessions/me',
            '/backend/api/v1/submitters/me',
            '/backend/api/v1/submitters/me/usage',
            '/backend/api/v1/submitter-sessions:logout',
        ])
        for (const [, init] of fetchMock.mock.calls) {
            expect(init.headers).toMatchObject({
                'X-Mass-Api-Key': expect.stringMatching(/^mass_/),
            })
            expect(init.headers).not.toHaveProperty('X-Mass-User-Mode')
        }
    })
})
