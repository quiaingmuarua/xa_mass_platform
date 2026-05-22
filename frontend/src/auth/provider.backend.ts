import {ApiError, requestApiData} from '@/api/http'
import type {AuthProvider} from '@/auth/provider'
import type {AuthUser} from '@/types/auth'

export const backendAuthProvider: AuthProvider = {
    async loadCurrentUser() {
        try {
            return await requestApiData<AuthUser>('/api/v1/auth/me')
        } catch (error) {
            if (error instanceof ApiError && error.status === 401) {
                return null
            }

            throw error
        }
    },
    async login() {
        throw new Error('Backend login page is not implemented yet.')
    },
    async logout() {
        await requestApiData('/api/v1/auth/logout', {
            method: 'POST',
        })
    },
}
