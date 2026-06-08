export interface ProjectDefinition {
    tenantId?: string
    code: string
    name: string
    description: string
    enabled: boolean
    eventCodes: string[]
    ownerPrincipalId?: string | null
}
