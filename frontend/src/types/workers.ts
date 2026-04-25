export interface WorkerListItem {
    workerId: string
    status: string
    workerGroupId: string | null
    agentVersion: string | null
    supportedProjects: string[]
    supportedEventCodes: string[]
    eventBindings?: WorkerEventBindingItem[]
    transportHint?: string | null
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

export interface WorkerContextListItem {
    workerContextId: string
    workerId: string
    project: string | null
    status: string
    routingTags: string[]
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

export interface WorkerDebugMessageRecord {
    messageId: string
    replyToMessageId: string | null
    workerId: string
    direction: 'OUTBOUND' | 'INBOUND'
    project: string
    eventCode: string | null
    status: string
    payloadJson: string
    rawJson: string
    detail: string
    createdAt: number
    updatedAt: number
}

export interface WorkerDebugHistoryResponse {
    workerId: string
    items: WorkerDebugMessageRecord[]
}

export interface WorkerDebugSendRequest {
    workerId: string
    project?: string
    eventCode: string
    requestId?: string
    headers?: Record<string, string>
    principal?: {
        clientId?: string
        userId?: string
    }
    payload: Record<string, unknown>
}

export interface WorkerDebugSendResult {
    messageId: string
    workerId: string
    project: string
    eventCode: string
    requestId: string
}
