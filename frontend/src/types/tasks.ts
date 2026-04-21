export interface TaskListItem {
    id: string
    taskName: string
    project: string
    routingCode: string
    status: 'NEW' | 'READY' | 'RUNNING' | 'PAUSED' | 'BLOCKED' | 'TERMINAL'
    terminalReason: string | null
    successCount: number
    eligibleCount: number
    batchSize: number
    updatedAt: string
}

export interface TaskMessageView {
    msgId: string
    status: 'INIT' | 'ASSIGNED' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'EXPIRED'
    latestAttemptWorkerId: string | null
    latestAttemptWorkerContextId: string | null
    latestAttemptBatchId: string | null
    retryCount: number
    maxRetryCount: number
    finalReason: string | null
    input: Record<string, unknown>
    output: Record<string, unknown>
    errorMessage: string | null
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
    taskRoutingCode: string
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
    items: Array<Record<string, unknown>>
    stateValidation: TaskValidationSummary
    messages: TaskMessageView[]
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
