import {
    createApiKey,
    listApiKeyApplications,
    listApiKeys,
    listApiKeyUsage,
} from '@/api/api-keys'
import {listPermissions, listRoles} from '@/api/roles'
import {createUser, listUsers} from '@/api/users'
import {
    resetRuntimeConfigOverrides,
    setRuntimeConfigOverrides,
} from '@/app/config'

describe('system API mock adapters', () => {
    afterEach(() => {
        resetRuntimeConfigOverrides()
        vi.unstubAllGlobals()
    })

    it('serves users and roles without backend fetch in mock mode', async () => {
        const fetchMock = vi.fn()
        vi.stubGlobal('fetch', fetchMock)
        setRuntimeConfigOverrides({useMockApi: true})

        const [users, roles, permissions] = await Promise.all([
            listUsers(),
            listRoles(),
            listPermissions(),
        ])

        expect(users.length).toBeGreaterThan(0)
        expect(roles.length).toBeGreaterThan(0)
        expect(permissions).toContain('api-key:view')
        expect(fetchMock).not.toHaveBeenCalled()
    })

    it('serves API-key review data without backend fetch in mock mode', async () => {
        const fetchMock = vi.fn()
        vi.stubGlobal('fetch', fetchMock)
        setRuntimeConfigOverrides({useMockApi: true})

        const [credentials, applications] = await Promise.all([
            listApiKeys(),
            listApiKeyApplications(),
        ])
        const usage = await listApiKeyUsage(credentials[0].keyId)

        expect(credentials.length).toBeGreaterThan(0)
        expect(applications.length).toBeGreaterThan(0)
        expect(usage.total).toBeGreaterThanOrEqual(0)
        expect(fetchMock).not.toHaveBeenCalled()
    })

    it('mutates mock users and credentials in the frontend preview store', async () => {
        setRuntimeConfigOverrides({useMockApi: true})

        const createdUser = await createUser({
            userId: 'mock-created-user',
            displayName: 'Mock Created User',
        })
        const createdCredential = await createApiKey({
            principalId: 'mock-created-key',
            createdForUserId: createdUser.userId,
            projectScopes: ['publicProbe'],
            eventScopes: ['crawler.fetch-page'],
            permissions: ['task:create'],
        })

        expect(createdUser.displayName).toBe('Mock Created User')
        expect(createdCredential.rawSecret).toContain('mass_sk_mock_secret_')
        expect(
            (await listUsers()).some((user) => user.userId === createdUser.userId),
        ).toBe(true)
        expect(
            (await listApiKeys()).some(
                (credential) =>
                    credential.keyId === createdCredential.credential.keyId,
            ),
        ).toBe(true)
    })
})
