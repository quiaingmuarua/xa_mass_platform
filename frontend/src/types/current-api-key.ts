export type CurrentApiKeyState = 'available' | 'unauthorized' | 'unavailable'

export interface CurrentApiKeyProfile {
    principalId: string
    userId: string | null
    projectScope: string | null
    permissions: string[]
    projectScopes: string[]
    eventScopes: string[]
    attributes: Record<string, string>
}

export interface CurrentApiKeySnapshot {
    state: CurrentApiKeyState
    profile: CurrentApiKeyProfile | null
}
