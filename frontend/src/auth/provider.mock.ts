import { mockAdminUser } from '@/auth/mock-user'
import type { AuthProvider } from '@/auth/provider'

export const mockAuthProvider: AuthProvider = {
    async loadCurrentUser() {
        return mockAdminUser
    },
    async login() {
        return mockAdminUser
    },
    async logout() {
        return Promise.resolve()
    },
}
