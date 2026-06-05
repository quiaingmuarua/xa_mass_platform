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

const backendAuthConfig = ref<BackendAuthConfig>(defaultConfig)
const csrfToken = ref<string | null>(null)

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
        csrfToken.value = null
    }
}

export function currentBackendAuthConfig(): BackendAuthConfig {
    return backendAuthConfig.value
}

export function setOperatorCsrfToken(token: string | null): void {
    const normalized = token?.trim()
    csrfToken.value = normalized && normalized.length > 0 ? normalized : null
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
    csrfToken.value = null
}

export function resetBackendAuthRuntime(): void {
    backendAuthConfig.value = defaultConfig
    csrfToken.value = null
}

function normalizeAuthMode(mode: string): BackendAuthMode {
    if (mode === 'session' || mode === 'disabled' || mode === 'dev-header') {
        return mode
    }
    return 'disabled'
}
