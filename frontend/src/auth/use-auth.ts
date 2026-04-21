import { computed, unref } from 'vue'
import { getAuthProvider } from '@/auth/provider'
import { useAuthState } from '@/stores/auth'
import type { AuthUser } from '@/types/auth'

export async function initializeAuth(): Promise<void> {
    const { setCurrentUser, markInitialized } = useAuthState()
    const provider = getAuthProvider()
    const user = await provider.loadCurrentUser()
    setCurrentUser(user)
    markInitialized(true)
}

export async function login(): Promise<void> {
    const provider = getAuthProvider()
    await provider.login()
}

export async function logout(): Promise<void> {
    const { setCurrentUser } = useAuthState()
    const provider = getAuthProvider()
    await provider.logout()
    setCurrentUser(null)
}

export function setMockCurrentUser(user: AuthUser | null): void {
    const { setCurrentUser, markInitialized } = useAuthState()
    setCurrentUser(user)
    markInitialized(true)
}

export function resetMockAuth(): void {
    const { resetAuthState } = useAuthState()
    resetAuthState()
}

export function useAuth() {
    const { authState, authSnapshot } = useAuthState()

    const user = computed(() => unref(authState.currentUser))
    const isAuthenticated = computed(() => user.value !== null)

    return {
        state: authState,
        snapshot: authSnapshot,
        user,
        isAuthenticated,
    }
}
