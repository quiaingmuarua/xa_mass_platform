export interface WorkerListItem {
    workerId: string
    runtimeStatus: string
    reachability?: string
    reachable?: boolean
    workerGroupId: string | null
    agentVersion: string | null
    supportedProjects: string[]
    supportedEventCodes: string[]
    eventBindings?: WorkerEventBindingItem[]
    transportHint?: string | null
    maxConcurrentWork?: number
    attributes: Record<string, string>
    locked: boolean
    fieldSources?: Record<string, string>
}

export interface WorkerEventBindingItem {
    eventCode: string
    projectCodes: string[]
}

export interface WorkerListResponse {
    items: WorkerListItem[]
    total: number
}
