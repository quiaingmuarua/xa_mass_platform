import { getAppConfig } from '@/app/config'
import { backendAuthProvider } from '@/auth/provider.backend'
import { mockAuthProvider } from '@/auth/provider.mock'
import type { AuthUser } from '@/types/auth'

export interface AuthProvider {
    loadCurrentUser(): Promise<AuthUser | null>
    login(): Promise<void>
    logout(): Promise<void>
}

export function getAuthProvider(): AuthProvider {
    if (getAppConfig().useMockAuth) {
        return mockAuthProvider
    }

    return backendAuthProvider
}
