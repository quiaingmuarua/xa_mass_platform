import { appConfig } from '@/app/config'
import { mockAuthProvider } from '@/auth/provider.mock'
import type { AuthUser } from '@/types/auth'

export interface AuthProvider {
    loadCurrentUser(): Promise<AuthUser | null>
    login(): Promise<void>
    logout(): Promise<void>
}

const unimplementedAuthProvider: AuthProvider = {
    async loadCurrentUser() {
        return null
    },
    async login() {
        throw new Error('Backend auth provider is not implemented yet.')
    },
    async logout() {
        return Promise.resolve()
    },
}

export function getAuthProvider(): AuthProvider {
    if (appConfig.useMockAuth) {
        return mockAuthProvider
    }

    return unimplementedAuthProvider
}
