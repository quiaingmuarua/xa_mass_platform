function readBooleanEnv(
    value: string | undefined,
    defaultValue: boolean,
): boolean {
    if (value === undefined) {
        return defaultValue
    }

    return value.trim().toLowerCase() === 'true'
}

export const appConfig = {
    appTitle: import.meta.env.VITE_APP_TITLE ?? 'XA Mass Control Console',
    apiBaseUrl: import.meta.env.VITE_API_BASE_URL ?? '/api',
    useMockApi: readBooleanEnv(import.meta.env.VITE_USE_MOCK_API, true),
    useMockAuth: readBooleanEnv(import.meta.env.VITE_USE_MOCK_AUTH, true),
    wsBaseUrl: import.meta.env.VITE_WS_BASE_URL ?? 'ws://localhost:18088/ws',
}
