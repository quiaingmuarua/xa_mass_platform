import { ApiError, requestApiData } from '@/api/http'
import {
    clearOperatorSessionAuth,
    setBackendAuthConfig,
    setOperatorCsrfToken,
    type BackendAuthConfig,
} from '@/auth/backend-auth'
import type { AuthProvider, LoginCredentials } from '@/auth/provider'
import type { AuthUser } from '@/types/auth'

interface OperatorLoginResponse {
    user: AuthUser
    csrfToken: string
}

interface CurrentOperatorResponse extends AuthUser {
    csrfToken?: string | null
}

export const backendAuthProvider: AuthProvider = {
    async loadCurrentUser() {
        return loadCurrentUser()
    },
    async login(credentials: LoginCredentials) {
        const config = await loadBackendAuthConfig()
        if (config.authMode === 'dev-header') {
            const user = await loadCurrentUser()
            if (!user) {
                throw new Error(
                    'Backend dev-header auth did not return a user.',
                )
            }
            return user
        }
        if (config.authMode !== 'session') {
            throw new Error('Operator login is disabled.')
        }

        const response = await requestApiData<OperatorLoginResponse>(
            '/api/v1/auth/login',
            {
                method: 'POST',
                includeOperatorAuth: false,
                credentials: 'same-origin',
                body: JSON.stringify({
                    userId: credentials.userId,
                    password: credentials.password,
                }),
            },
        )
        setOperatorCsrfToken(response.csrfToken)
        return response.user
    },
    async logout() {
        try {
            await requestApiData('/api/v1/auth/logout', {
                method: 'POST',
            })
        } finally {
            clearOperatorSessionAuth()
        }
    },
}

export async function loadBackendAuthConfig(): Promise<BackendAuthConfig> {
    const config = await requestApiData<BackendAuthConfig>(
        '/api/v1/auth/config',
        {
            includeOperatorAuth: false,
        },
    )
    setBackendAuthConfig(config)
    return config
}

async function loadCurrentUser(): Promise<AuthUser | null> {
    const config = await loadBackendAuthConfig()
    if (config.authMode === 'disabled') {
        return null
    }
    try {
        const current = await requestApiData<CurrentOperatorResponse>(
            '/api/v1/auth/me',
        )
        if (config.authMode === 'session') {
            setOperatorCsrfToken(current.csrfToken ?? null)
        }
        return current
    } catch (error) {
        if (error instanceof ApiError && error.status === 401) {
            return null
        }

        throw error
    }
}
