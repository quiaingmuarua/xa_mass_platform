export interface WorkerListItem {
    workerId: string
    status: string
    workerGroupId: string | null
    agentVersion: string | null
    supportedProjects: string[]
    attributes: Record<string, string>
    lastHeartbeat: string
    locked: boolean
    updateTime: string
}

export interface WorkerContextListItem {
    workerContextId: string
    workerId: string
    status: string
    channel: string | null
    attributes: Record<string, string>
    lastBindTaskId: string | null
    lastUsedTime: string
    updateTime: string
}

export interface WorkerListResponse {
    items: WorkerListItem[]
    total: number
}

export interface WorkerContextListResponse {
    items: WorkerContextListItem[]
    total: number
}
