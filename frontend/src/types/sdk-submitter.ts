export type SdkSubmitterState = 'available' | 'unauthorized' | 'unavailable'

export interface SdkSubmitterProfile {
    principalId: string
    userId: string | null
    projectScope: string | null
    permissions: string[]
    projectScopes: string[]
    eventScopes: string[]
    attributes: Record<string, string>
}

export interface SdkSubmitterSnapshot {
    state: SdkSubmitterState
    profile: SdkSubmitterProfile | null
}
