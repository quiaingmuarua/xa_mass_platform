import { computed, readonly, ref } from 'vue'

export type BackendAuthMode = 'dev-header' | 'session' | 'disabled'

export interface BackendAuthConfig {
    authMode: BackendAuthMode
    operatorHeaderSupported: boolean
    sessionCookieSupported: boolean
    csrfHeaderName?: string | null
}

const defaultConfig: BackendAuthConfig = {
    authMode: 'dev-header',
    operatorHeaderSupported: true,
    sessionCookieSupported: false,
    csrfHeaderName: null,
}

const CSRF_STORAGE_KEY = 'xa.mass.operator.csrf-token'

const backendAuthConfig = ref<BackendAuthConfig>(defaultConfig)
const csrfToken = ref<string | null>(readStoredCsrfToken())

export const backendAuthSnapshot = readonly(backendAuthConfig)
export const operatorAuthMode = computed(() => backendAuthConfig.value.authMode)
export const isDevHeaderAuth = computed(
    () => backendAuthConfig.value.operatorHeaderSupported,
)

export function setBackendAuthConfig(config: BackendAuthConfig): void {
    backendAuthConfig.value = {
        authMode: normalizeAuthMode(config.authMode),
        operatorHeaderSupported: config.operatorHeaderSupported,
        sessionCookieSupported: config.sessionCookieSupported,
        csrfHeaderName: config.csrfHeaderName ?? null,
    }
    if (!backendAuthConfig.value.sessionCookieSupported) {
        setOperatorCsrfToken(null)
    }
}

export function currentBackendAuthConfig(): BackendAuthConfig {
    return backendAuthConfig.value
}

export function setOperatorCsrfToken(token: string | null): void {
    const normalized = token?.trim()
    const next = normalized && normalized.length > 0 ? normalized : null
    csrfToken.value = next
    writeStoredCsrfToken(next)
}

export function currentOperatorCsrfHeader(): Record<string, string> {
    const config = backendAuthConfig.value
    if (
        !config.sessionCookieSupported ||
        !config.csrfHeaderName ||
        !csrfToken.value
    ) {
        return {}
    }

    return {
        [config.csrfHeaderName]: csrfToken.value,
    }
}

export function clearOperatorSessionAuth(): void {
    setOperatorCsrfToken(null)
}

export function resetBackendAuthRuntime(): void {
    backendAuthConfig.value = defaultConfig
    setOperatorCsrfToken(null)
}

export function reloadOperatorCsrfTokenFromStorage(): void {
    csrfToken.value = readStoredCsrfToken()
}

function normalizeAuthMode(mode: string): BackendAuthMode {
    if (mode === 'session' || mode === 'disabled' || mode === 'dev-header') {
        return mode
    }
    return 'disabled'
}

function readStoredCsrfToken(): string | null {
    try {
        const stored = globalThis.sessionStorage?.getItem(CSRF_STORAGE_KEY)
        const normalized = stored?.trim()
        return normalized && normalized.length > 0 ? normalized : null
    } catch {
        return null
    }
}

function writeStoredCsrfToken(token: string | null): void {
    try {
        if (!token) {
            globalThis.sessionStorage?.removeItem(CSRF_STORAGE_KEY)
            return
        }
        globalThis.sessionStorage?.setItem(CSRF_STORAGE_KEY, token)
    } catch {
        // Session auth still works for the current page even if storage is unavailable.
    }
}
