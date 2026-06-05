function readBooleanEnv(
    value: string | undefined,
    defaultValue: boolean,
): boolean {
    if (value === undefined) {
        return defaultValue
    }

    return value.trim().toLowerCase() === 'true'
}

export interface AppConfig {
    appTitle: string
    apiBaseUrl: string
    apiDocsUrl: string
    useMockApi: boolean
    useMockAuth: boolean
    wsBaseUrl: string
}

const envConfig: AppConfig = {
    appTitle: import.meta.env.VITE_APP_TITLE ?? 'XA Mass Control Console',
    apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? '',
    apiDocsUrl: import.meta.env.VITE_API_DOCS_URL ?? '/doc.html#/home',
    // Default to mock mode in `vite dev`, but use the real backend when serving a production build.
    useMockApi: readBooleanEnv(import.meta.env.VITE_USE_MOCK_API, import.meta.env.DEV),
    useMockAuth: readBooleanEnv(import.meta.env.VITE_USE_MOCK_AUTH, import.meta.env.DEV),
    wsBaseUrl: import.meta.env.VITE_WS_BASE_URL ?? 'ws://localhost:18088/ws',
}

let runtimeConfigOverrides: Partial<AppConfig> = {}

export function getAppConfig(): AppConfig {
    return {
        ...envConfig,
        ...runtimeConfigOverrides,
    }
}

export function setRuntimeConfigOverrides(overrides: Partial<AppConfig>): void {
    runtimeConfigOverrides = {
        ...runtimeConfigOverrides,
        ...overrides,
    }
}

export function resetRuntimeConfigOverrides(): void {
    runtimeConfigOverrides = {}
}
