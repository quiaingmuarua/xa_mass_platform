export interface WorkerListItem {
    workerId: string
    status: string
    transportReachability?: string
    transportOnline?: boolean
    workerGroupId: string | null
    adapterNodeId?: string | null
    agentVersion: string | null
    supportedProjects: string[]
    supportedEventCodes: string[]
    eventBindings?: WorkerEventBindingItem[]
    transportHint?: string | null
    maxConcurrentWork?: number
    attributes: Record<string, string>
    lastHeartbeat: string
    locked: boolean
    connections?: WorkerConnectionItem[]
    hasActiveEndpoint?: boolean
    updateTime: string
}

export interface WorkerEventBindingItem {
    eventCode: string
    projectCodes: string[]
}

export interface WorkerConnectionItem {
    active: boolean
    endpointId: string | null
    transport: string | null
}

export interface WorkerListResponse {
    items: WorkerListItem[]
    total: number
}
