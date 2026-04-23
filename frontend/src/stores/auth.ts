import {computed, readonly, ref} from 'vue'
import type {AuthState, AuthUser} from '@/types/auth'

const currentUser = ref<AuthUser | null>(null)
const initialized = ref(false)

function setCurrentUser(user: AuthUser | null): void {
    currentUser.value = user
}

function markInitialized(value: boolean): void {
    initialized.value = value
}

function resetAuthState(): void {
    currentUser.value = null
    initialized.value = false
}

const authState = readonly({
    currentUser,
    initialized,
})

const authSnapshot = computed<AuthState>(() => ({
    currentUser: currentUser.value,
    initialized: initialized.value,
}))

export function useAuthState() {
    return {
        authState,
        authSnapshot,
        setCurrentUser,
        markInitialized,
        resetAuthState,
    }
}
