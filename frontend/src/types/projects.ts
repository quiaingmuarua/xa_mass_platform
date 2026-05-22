export interface ProjectDefinition {
    tenantId?: string
    code: string
    name: string
    description: string
    enabled: boolean
    eventCodes: string[]
    ownerPrincipalId?: string | null
}

export interface ProjectSubmitterProfile {
    principalId: string
    principalType: string
    keyPrefix: string | null
    userId: string | null
    projectScope: string | null
    permissions: string[]
    projectScopes: string[]
    eventScopes: string[]
    enabled: boolean
    attributes: Record<string, string>
}
