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

export interface TaskExecutionSpec {
    profile?: 'STANDARD' | 'LATENCY_SENSITIVE' | 'IDEMPOTENT_BATCH'
    workloadClass?: 'INTERACTIVE' | 'BULK'
    batchSize?: number
    maxRuntimeSeconds?: number
    defaultMaxRetryCount?: number
}

export interface TaskShellCreateRequest {
    userId: string
    project: string
    sharedConfig: Record<string, unknown>
    executionSpec?: TaskExecutionSpec
    batchSize?: number
    maxRuntimeSeconds?: number
    sourceType?: 'STREAM' | 'FILE'
    sourceRef?: string
}

export interface TaskShellCreateResult {
    taskId: string
    message: string
}

export interface TaskItemBatchAppendRequest {
    eventCode?: string
    items: Array<Record<string, unknown>>
}

export interface TaskDebugSyncRequest {
    userId: string
    project: string
    eventCode: string
    sharedConfig: Record<string, unknown>
    items: Array<Record<string, unknown>>
    batchSize?: number
    maxRuntimeSeconds: number
    workloadClass?: 'INTERACTIVE' | 'BULK'
    taskName?: string
    payloadType?: 'TEXT' | 'JSON'
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
