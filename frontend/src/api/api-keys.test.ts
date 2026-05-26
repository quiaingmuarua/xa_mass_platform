import {
    approveApiKeyApplication,
    createApiKey,
    createApiKeyApplication,
    getApiKey,
    getApiKeyApplication,
    listApiKeyApplications,
    listApiKeys,
    rejectApiKeyApplication,
    revokeApiKey,
} from '@/api/api-keys'
import {setRuntimeConfigOverrides} from '@/app/config'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('api-key API client', () => {
    it('uses the server IAM API-key routes', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        const fetchMock = vi.fn().mockImplementation(() =>
            Promise.resolve(jsonResponse({
                code: 0,
                msg: 'ok',
                data: [],
            })),
        )
        vi.stubGlobal('fetch', fetchMock)

        await listApiKeys()
        await getApiKey('ak-1')
        await listApiKeyApplications()
        await getApiKeyApplication('aka-1')

        expect(fetchMock.mock.calls.map(([input]) => input)).toEqual([
            '/backend/api/v1/api-keys',
            '/backend/api/v1/api-keys/ak-1',
            '/backend/api/v1/api-key-applications',
            '/backend/api/v1/api-key-applications/aka-1',
        ])
    })

    it('creates, revokes, applies, approves, and rejects through explicit endpoints', async () => {
        const fetchMock = vi.fn().mockImplementation(() =>
            Promise.resolve(jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    credential: {
                        keyId: 'ak-1',
                        principalId: 'crawler-key',
                        createdForUserId: 'ops-admin',
                        keyPrefix: 'mass_sk_123...',
                        projectScopes: ['crawlerApp'],
                        eventScopes: ['crawler.fetch-page'],
                        permissions: ['task:create'],
                        status: 'ACTIVE',
                        applicationId: null,
                        createdBy: 'ops-admin',
                        createdAt: '2026-05-26T00:00:00Z',
                        expiresAt: null,
                        revokedAt: null,
                        revokedBy: null,
                        revokeReason: null,
                        attributes: {},
                    },
                    rawSecret: 'mass_sk_secret',
                },
            })),
        )
        vi.stubGlobal('fetch', fetchMock)

        await createApiKey({
            principalId: 'crawler-key',
            createdForUserId: 'ops-admin',
            projectScopes: ['crawlerApp'],
            eventScopes: ['crawler.fetch-page'],
            permissions: ['task:create'],
        })
        await revokeApiKey('ak-1', 'rotated')
        await createApiKeyApplication({
            requestedPrincipalId: 'crawler-key',
            requestedUserId: 'ops-admin',
            requestedProjectScopes: ['crawlerApp'],
            requestedEventScopes: ['crawler.fetch-page'],
            requestedPermissions: ['task:create'],
            purpose: 'crawler',
        })
        await approveApiKeyApplication('aka-1', 'approved')
        await rejectApiKeyApplication('aka-2', 'rejected')

        expect(fetchMock.mock.calls.map(([input]) => input)).toEqual([
            '/api/v1/api-keys',
            '/api/v1/api-keys/ak-1:revoke',
            '/api/v1/api-key-applications',
            '/api/v1/api-key-applications/aka-1:approve',
            '/api/v1/api-key-applications/aka-2:reject',
        ])
    })
})
