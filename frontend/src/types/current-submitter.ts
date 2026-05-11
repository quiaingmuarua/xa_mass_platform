export type CurrentSubmitterState = 'available' | 'unauthorized' | 'unavailable'

export interface CurrentSubmitterProfile {
    principalId: string
    userId: string | null
    projectScope: string | null
    permissions: string[]
    projectScopes: string[]
    eventScopes: string[]
    attributes: Record<string, string>
}

export interface CurrentSubmitterSnapshot {
    state: CurrentSubmitterState
    profile: CurrentSubmitterProfile | null
}
