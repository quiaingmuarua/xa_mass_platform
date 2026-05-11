export type MetadataPayloadType = 'TEXT' | 'JSON'

export type MetadataTaskMode = 'SINGLE_RUN' | 'STREAMING'

export interface ProjectMetadata {
    tenantId?: string
    code: string
    name: string
    description: string
    enabled: boolean
    eventCodes: string[]
    ownerPrincipalId?: string | null
}

export interface SdkEventDefinition {
    code: string
    name: string
    description: string
    payloadTypes: MetadataPayloadType[]
    taskModes: MetadataTaskMode[]
    enabled: boolean
}

export type EventInvocationModel = 'TASK_BACKED' | 'DIRECT_RUNTIME'

export interface EventCapability {
    eventCode: string
    eventName: string
    enabled: boolean
    invocationModel: EventInvocationModel
    projectCodes: string[]
    workerIds: string[]
    onlineWorkerIds: string[]
    hasDirectRuntimeHandler: boolean
    hasOnlineWorkerCoverage: boolean
    ready: boolean
}

export interface ProjectSubmitterMetadata {
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
