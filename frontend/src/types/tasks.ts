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
    fieldSources?: Record<string, string>
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
    fieldSources?: Record<string, string>
}

export interface TaskDetailResponse {
    task: TaskDetailRecord
}

export interface TaskReviewSummary {
    totalItems: number
    successItems: number
    failedItems: number
    expiredItems: number
    processingItems: number
    previewCount: number
    previewLimit: number
    hasMore: boolean
}

export interface TaskSeedPreviewItem {
    messageId: string
    eventCode: string | null
    status: string | null
    payloadRef: string | null
    retryCount: number
    maxRetryCount: number
    createTime: string
    assignedTime: string
    input: Record<string, unknown> | null
}

export interface TaskResultPreviewItem {
    messageId: string
    eventCode: string | null
    status: string | null
    finalReason: string | null
    retryCount: number
    maxRetryCount: number
    workerId: string | null
    batchId: string | null
    attemptId: string | null
    startTime: string
    completeTime: string
    updateTime: string
    errorCode: string | null
    errorMessage: string | null
    output: Record<string, unknown> | null
}

export interface TaskReviewResponse {
    summary: TaskReviewSummary
    seedPreview: TaskSeedPreviewItem[]
    resultPreview: TaskResultPreviewItem[]
    exports: {
        seedUrl: string
        resultUrl: string
    }
}

export interface TaskListResponse {
    items: TaskListItem[]
    total: number
}

export interface TaskListQuery {
    keyword?: string
    project?: string
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
