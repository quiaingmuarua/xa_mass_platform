export type PayloadType = 'TEXT' | 'JSON'

export type TaskMode = 'SINGLE_RUN' | 'STREAMING'

export interface EventDefinition {
    code: string
    name: string
    description: string
    payloadTypes: PayloadType[]
    taskModes: TaskMode[]
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
