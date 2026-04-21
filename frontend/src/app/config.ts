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
    useMockApi: boolean
    useMockAuth: boolean
    wsBaseUrl: string
}

const envConfig: AppConfig = {
    appTitle: import.meta.env.VITE_APP_TITLE ?? 'XA Mass Control Console',
    apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? '',
    useMockApi: readBooleanEnv(import.meta.env.VITE_USE_MOCK_API, true),
    useMockAuth: readBooleanEnv(import.meta.env.VITE_USE_MOCK_AUTH, true),
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
