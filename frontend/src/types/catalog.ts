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
    declaredWorkerIds: string[]
    reachableWorkerIds: string[]
    hasDirectRuntimeHandler: boolean
    hasReachableWorkerCoverage: boolean
    hasInvocationCoverage: boolean
}

export interface WorkerGroupCapability {
    groupId: string
    eventBindings: WorkerGroupEventBinding[]
    projectCodes: string[]
    defaultAttributes: Record<string, string>
    defaultMaxConcurrentWork: number
    adapterNodes: AdapterNodeCapability[]
    nodeGroupBindings: NodeGroupBindingCapability[]
    workerCount: number
    declaredWorkerIds: string[]
    transportCounts: Record<string, number>
    reachableWorkerCountsByTransport: Record<string, number>
    runtimeStatusCounts: Record<string, number>
    lockedCount: number
    reachableUnlockedBindingCount: number
    fingerprintDistribution: Record<string, number>
}

export interface WorkerGroupEventBinding {
    eventCode: string
    projectCodes: string[]
}

export interface AdapterNodeCapability {
    adapterNodeId: string
    adapterType: string | null
    adapterVersion: string | null
    endpointId: string | null
    enabled: boolean
    online: boolean
    attributes: Record<string, string>
}

export interface NodeGroupBindingCapability {
    adapterNodeId: string
    workerGroupId: string
    pluginVersion: string | null
    deploymentVersion: string | null
    enabled: boolean
    draining: boolean
    attributes: Record<string, string>
}
