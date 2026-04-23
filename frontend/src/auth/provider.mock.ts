import {mockAdminUser} from '@/auth/mock-user'
import type {AuthProvider} from '@/auth/provider'

export const mockAuthProvider: AuthProvider = {
    async loadCurrentUser() {
        return mockAdminUser
    },
    async login() {
        throw new Error('Mock auth does not implement interactive login.')
    },
    async logout() {
        return Promise.resolve()
    },
}
