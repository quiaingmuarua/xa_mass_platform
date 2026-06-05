import { getAppConfig } from '@/app/config'
import { backendAuthProvider } from '@/auth/provider.backend'
import { mockAuthProvider } from '@/auth/provider.mock'
import type { AuthUser } from '@/types/auth'

export interface LoginCredentials {
    userId: string
    password: string
}

export interface AuthProvider {
    loadCurrentUser(): Promise<AuthUser | null>
    login(credentials: LoginCredentials): Promise<AuthUser>
    logout(): Promise<void>
}

export function getAuthProvider(): AuthProvider {
    if (getAppConfig().useMockAuth) {
        return mockAuthProvider
    }

    return backendAuthProvider
}
