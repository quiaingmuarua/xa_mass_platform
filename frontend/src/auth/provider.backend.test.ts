import {backendAuthProvider} from '@/auth/provider.backend'
import {setRuntimeConfigOverrides} from '@/app/config'
import {useOperatorMode} from '@/auth/operator-mode'

function jsonResponse(body: unknown, status = 200): Response {
    return new Response(JSON.stringify(body), {
        status,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('backendAuthProvider', () => {
    it('loads the current user from /api/v1/auth/me', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        const { setOperatorMode } = useOperatorMode()
        setOperatorMode('admin')
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
            '/backend/api/v1/auth/me',
            expect.objectContaining({
                headers: expect.objectContaining({
                    'X-Mass-User-Mode': 'admin',
                }),
            }),
        )
        expect(user?.id).toBe('ops-admin')
        expect(user?.permissions).toEqual(['task:view'])
    })

    it('returns null when /api/v1/auth/me is unauthorized', async () => {
        setRuntimeConfigOverrides({
            useMockAuth: false,
        })
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

    it('throws when /api/v1/auth/me fails for non-auth reasons', async () => {
        setRuntimeConfigOverrides({
            useMockAuth: false,
        })
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
            'backend failed',
        )
    })
})
