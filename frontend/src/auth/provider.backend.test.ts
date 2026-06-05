import { backendAuthProvider } from '@/auth/provider.backend'
import { setRuntimeConfigOverrides } from '@/app/config'
import { currentOperatorCsrfHeader } from '@/auth/backend-auth'
import { useOperatorMode } from '@/auth/operator-mode'

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
        const fetchMock = vi
            .fn()
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        authMode: 'dev-header',
                        operatorHeaderSupported: true,
                        sessionCookieSupported: false,
                    },
                }),
            )
            .mockResolvedValueOnce(
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

        expect(fetchMock).toHaveBeenNthCalledWith(
            1,
            '/backend/api/v1/auth/config',
            expect.objectContaining({
                credentials: 'omit',
            }),
        )
        expect(fetchMock).toHaveBeenNthCalledWith(
            2,
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
            vi
                .fn()
                .mockResolvedValueOnce(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            authMode: 'session',
                            operatorHeaderSupported: false,
                            sessionCookieSupported: true,
                            csrfHeaderName: 'X-Mass-Csrf-Token',
                        },
                    }),
                )
                .mockResolvedValueOnce(
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
            vi
                .fn()
                .mockResolvedValueOnce(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            authMode: 'session',
                            operatorHeaderSupported: false,
                            sessionCookieSupported: true,
                            csrfHeaderName: 'X-Mass-Csrf-Token',
                        },
                    }),
                )
                .mockResolvedValueOnce(
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

    it('logs in with session credentials and stores the CSRF token', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        const fetchMock = vi
            .fn()
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        authMode: 'session',
                        operatorHeaderSupported: false,
                        sessionCookieSupported: true,
                        csrfHeaderName: 'X-Mass-Csrf-Token',
                    },
                }),
            )
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        user: {
                            id: 'ops-admin',
                            name: 'Ops Admin',
                            email: 'ops-admin@example.internal',
                            roles: ['OPS_ADMIN'],
                            permissions: ['task:view'],
                        },
                        csrfToken: 'csrf-token-1',
                    },
                }),
            )
        vi.stubGlobal('fetch', fetchMock)

        const user = await backendAuthProvider.login({
            userId: 'ops-admin',
            password: 'secret',
        })

        expect(fetchMock).toHaveBeenNthCalledWith(
            2,
            '/backend/api/v1/auth/login',
            expect.objectContaining({
                method: 'POST',
                credentials: 'same-origin',
                body: JSON.stringify({
                    userId: 'ops-admin',
                    password: 'secret',
                }),
                headers: expect.not.objectContaining({
                    'X-Mass-User-Mode': expect.any(String),
                }),
            }),
        )
        expect(user.id).toBe('ops-admin')
        expect(currentOperatorCsrfHeader()).toEqual({
            'X-Mass-Csrf-Token': 'csrf-token-1',
        })
    })

    it('sends CSRF on session logout and clears it afterwards', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
            useMockAuth: false,
        })
        const fetchMock = vi
            .fn()
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        authMode: 'session',
                        operatorHeaderSupported: false,
                        sessionCookieSupported: true,
                        csrfHeaderName: 'X-Mass-Csrf-Token',
                    },
                }),
            )
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        user: {
                            id: 'ops-admin',
                            name: 'Ops Admin',
                            email: 'ops-admin@example.internal',
                            roles: ['OPS_ADMIN'],
                            permissions: ['task:view'],
                        },
                        csrfToken: 'csrf-token-1',
                    },
                }),
            )
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        message: 'Logout acknowledged',
                    },
                }),
            )
        vi.stubGlobal('fetch', fetchMock)

        await backendAuthProvider.login({
            userId: 'ops-admin',
            password: 'secret',
        })
        await backendAuthProvider.logout()

        expect(fetchMock).toHaveBeenNthCalledWith(
            3,
            '/backend/api/v1/auth/logout',
            expect.objectContaining({
                method: 'POST',
                credentials: 'same-origin',
                headers: expect.objectContaining({
                    'X-Mass-Csrf-Token': 'csrf-token-1',
                }),
            }),
        )
        expect(currentOperatorCsrfHeader()).toEqual({})
    })
})
