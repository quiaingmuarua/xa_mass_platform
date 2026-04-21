import { backendAuthProvider } from '@/auth/provider.backend'
import { setRuntimeConfigOverrides } from '@/app/config'

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('backendAuthProvider', () => {
    it('loads the current user from /api/auth/me', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
        })
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    id: 'ops-admin',
                    name: 'Ops Admin',
                    email: 'ops-admin@example.internal',
                    roles: ['OPS_ADMIN'],
                    permissions: ['task:view'],
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const user = await backendAuthProvider.loadCurrentUser()

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/auth/me',
            expect.any(Object),
        )
        expect(user?.id).toBe('ops-admin')
        expect(user?.permissions).toEqual(['task:view'])
    })

    it('returns null when /api/auth/me is unauthorized', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                jsonResponse(
                    {
                        code: 401,
                        msg: 'Authentication is required',
                        data: null,
                    },
                    401,
                ),
            ),
        )

        await expect(backendAuthProvider.loadCurrentUser()).resolves.toBeNull()
    })

    it('throws when /api/auth/me fails for non-auth reasons', async () => {
        vi.stubGlobal(
            'fetch',
            vi.fn().mockResolvedValue(
                jsonResponse(
                    {
                        code: 500,
                        msg: 'backend failed',
                        data: null,
                    },
                    500,
                ),
            ),
        )

        await expect(backendAuthProvider.loadCurrentUser()).rejects.toThrow(
            'Request failed: 500',
        )
    })
})
