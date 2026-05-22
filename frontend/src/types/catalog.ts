export type PayloadType = 'TEXT' | 'JSON'

export type TaskMode = 'SINGLE_RUN' | 'STREAMING'

export type PriorityClass = 'CONTROL' | 'INTERACTIVE' | 'STANDARD' | 'BULK'

export type ResponseMode = 'NONE' | 'ACK' | 'FINAL_RESULT' | 'STREAM'

export type TargetScope = 'WORKER' | 'TASK_ENGINE' | 'OPERATOR' | 'WORKER_MANAGER'

export interface EventDefinition {
    code: string
    name: string
    description: string
    payloadTypes: PayloadType[]
    taskModes: TaskMode[]
    enabled: boolean
    priorityClass: PriorityClass
    responseMode: ResponseMode
    targetScope: TargetScope
}

export type EventInvocationModel = 'TASK_BACKED' | 'DIRECT_RUNTIME'

export interface EventCapability {
    eventCode: string
    eventName: string
    enabled: boolean
    priorityClass: PriorityClass
    responseMode: ResponseMode
    targetScope: TargetScope
    invocationModel: EventInvocationModel
    projectCodes: string[]
    workerIds: string[]
    onlineWorkerIds: string[]
    hasDirectRuntimeHandler: boolean
    hasOnlineWorkerCoverage: boolean
    ready: boolean
}
