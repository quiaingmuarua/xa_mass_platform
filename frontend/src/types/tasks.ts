export interface TaskListItem {
    id: string
    taskName: string
    project: string
    status: 'NEW' | 'READY' | 'RUNNING' | 'PAUSED' | 'BLOCKED' | 'TERMINAL'
    terminalReason: string | null
    successCount: number
    eligibleCount: number
    batchSize: number
    updatedAt: string
}

export interface TaskValidationSummary {
    valid: boolean
    needsResolution: boolean
    totalMessages: number
    successMessages: number
    failedMessages: number
    processingMessages: number
    violations: string[]
}

export interface TaskDetailRecord {
    tid: string
    taskName: string
    project: string
    status: TaskListItem['status']
    terminalReason: string | null
    batchSize: number
    sharedConfig: Record<string, unknown>
    user: {
        name: string
    }
    taskTargetNumber: number
    taskEligibleNumber: number
    taskSuccessNumber: number
    taskNonSuccessNumber: number
    peakAssignedWorkerCount: number
    createTime: string
    updateTime: string
}

export interface TaskDetailResponse {
    task: TaskDetailRecord
    stateValidation: TaskValidationSummary
}

export interface TaskListResponse {
    items: TaskListItem[]
    total: number
}

export interface TaskListQuery {
    keyword?: string
    status?: TaskListItem['status'] | ''
}

export interface TaskActionResult {
    message: string
    newStatus?: TaskListItem['status']
    terminalReason?: string
}

export interface TaskShellCreateRequest {
    userId: string
    project: string
    taskName: string
    eventCode?: string
    mode?: 'SINGLE_RUN' | 'STREAMING'
    payloadType?: 'TEXT' | 'JSON'
    sharedConfig: Record<string, unknown>
    batchSize: number
    maxRuntimeSeconds: number
}

export interface TaskShellCreateResult {
    taskId: string
    message: string
}

export interface TaskItemBatchAppendRequest {
    items: Array<Record<string, unknown>>
    defaultMsgMaxRetryCount: number
}

export interface TaskDebugSyncRequest {
    userId: string
    project: string
    taskName: string
    eventCode: string
    payloadType?: 'TEXT' | 'JSON'
    sharedConfig: Record<string, unknown>
    inputs: Array<Record<string, unknown>>
    maxRuntimeSeconds: number
}

export interface TaskDebugSyncResult {
    taskId: string
    messageId: string
    synced: boolean
    timedOut: boolean
    timeoutMs?: number
    status?: string
    output?: Record<string, unknown>
    errorCode?: string
    errorMessage?: string
}
