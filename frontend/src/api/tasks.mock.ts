import type {
    TaskActionResult,
    TaskDebugSyncRequest,
    TaskDebugSyncResult,
    TaskDetailResponse,
    TaskItemBatchAppendRequest,
    TaskListItem,
    TaskListQuery,
    TaskListResponse,
    TaskReviewResponse,
    TaskShellCreateRequest,
    TaskShellCreateResult,
} from '@/types/tasks'

const mockTaskList: TaskListItem[] = [
    {
        id: 'task-001',
        taskName: 'Warm worker pool',
        project: 'demoApp',
        status: 'RUNNING',
        terminalReason: null,
        successCount: 6,
        eligibleCount: 10,
        batchSize: 2,
        updatedAt: '2026-04-20 11:24:00',
    },
    {
        id: 'task-002',
        taskName: 'Review failed delivery backlog',
        project: 'demoApp',
        status: 'PAUSED',
        terminalReason: null,
        successCount: 2,
        eligibleCount: 8,
        batchSize: 1,
        updatedAt: '2026-04-20 10:51:00',
    },
    {
        id: 'task-003',
        taskName: 'Daily worker session refresh',
        project: 'testApp',
        status: 'TERMINAL',
        terminalReason: 'ALL_MESSAGES_SUCCEEDED',
        successCount: 12,
        eligibleCount: 12,
        batchSize: 3,
        updatedAt: '2026-04-20 09:18:00',
    },
]

const mockTaskDetails: Record<string, TaskDetailResponse> = {
    'task-001': {
        task: {
            tid: 'task-001',
            taskName: 'Warm worker pool',
            project: 'demoApp',
            status: 'RUNNING',
            terminalReason: null,
            batchSize: 2,
            sharedConfig: {
                objective: 'stabilize dispatch throughput',
                targetConcurrency: 4,
            },
            user: {
                name: 'ops-admin',
            },
            taskTargetNumber: 10,
            taskEligibleNumber: 10,
            taskSuccessNumber: 6,
            taskNonSuccessNumber: 4,
            peakAssignedWorkerCount: 4,
            createTime: '2026-04-20 08:30:00',
            updateTime: '2026-04-20 11:24:00',
        },
    },
    'task-002': {
        task: {
            tid: 'task-002',
            taskName: 'Review failed delivery backlog',
            project: 'demoApp',
            status: 'PAUSED',
            terminalReason: null,
            batchSize: 1,
            sharedConfig: {
                objective: 'manual review of retry-heavy messages',
            },
            user: {
                name: 'ops-admin',
            },
            taskTargetNumber: 8,
            taskEligibleNumber: 8,
            taskSuccessNumber: 2,
            taskNonSuccessNumber: 6,
            peakAssignedWorkerCount: 2,
            createTime: '2026-04-19 23:10:00',
            updateTime: '2026-04-20 10:51:00',
        },
    },
    'task-003': {
        task: {
            tid: 'task-003',
            taskName: 'Daily worker session refresh',
            project: 'testApp',
            status: 'TERMINAL',
            terminalReason: 'ALL_MESSAGES_SUCCEEDED',
            batchSize: 3,
            sharedConfig: {
                objective: 'session rotation',
            },
            user: {
                name: 'ops-admin',
            },
            taskTargetNumber: 12,
            taskEligibleNumber: 12,
            taskSuccessNumber: 12,
            taskNonSuccessNumber: 0,
            peakAssignedWorkerCount: 3,
            createTime: '2026-04-20 05:00:00',
            updateTime: '2026-04-20 09:18:00',
        },
    },
}

const mockTaskReviews: Record<string, TaskReviewResponse> = {
    'task-001': {
        summary: {
            totalItems: 10,
            successItems: 6,
            failedItems: 1,
            expiredItems: 0,
            processingItems: 3,
            previewCount: 3,
            previewLimit: 12,
            hasMore: true,
        },
        seedPreview: [
            {
                messageId: 'msg-001',
                eventCode: 'demo.dispatch',
                status: 'SUCCESS',
                payloadRef: null,
                retryCount: 0,
                maxRetryCount: 3,
                createTime: '2026-04-20 08:31:00',
                assignedTime: '2026-04-20 08:31:03',
                input: { target: 'warmup-a', region: 'us' },
            },
            {
                messageId: 'msg-002',
                eventCode: 'demo.dispatch',
                status: 'SUCCESS',
                payloadRef: null,
                retryCount: 0,
                maxRetryCount: 3,
                createTime: '2026-04-20 08:31:02',
                assignedTime: '2026-04-20 08:31:05',
                input: { target: 'warmup-b', region: 'eu' },
            },
            {
                messageId: 'msg-003',
                eventCode: 'demo.dispatch',
                status: 'RUNNING',
                payloadRef: null,
                retryCount: 1,
                maxRetryCount: 3,
                createTime: '2026-04-20 08:31:05',
                assignedTime: '2026-04-20 08:31:08',
                input: { target: 'warmup-c', region: 'apac' },
            },
        ],
        resultPreview: [
            {
                messageId: 'msg-001',
                eventCode: 'demo.dispatch',
                status: 'SUCCESS',
                finalReason: 'BUSINESS_SUCCESS',
                retryCount: 0,
                maxRetryCount: 3,
                workerId: 'worker-demo-01',
                workerContextId: 'ctx-demo-us-01',
                batchId: 'batch-101',
                attemptId: 'attempt-101',
                startTime: '2026-04-20 08:31:04',
                completeTime: '2026-04-20 08:31:10',
                updateTime: '2026-04-20 08:31:10',
                errorCode: null,
                errorMessage: null,
                output: { status: 'ok', latencyMs: 612, value: 'warmed' },
            },
            {
                messageId: 'msg-002',
                eventCode: 'demo.dispatch',
                status: 'SUCCESS',
                finalReason: 'BUSINESS_SUCCESS',
                retryCount: 0,
                maxRetryCount: 3,
                workerId: 'worker-demo-03',
                workerContextId: 'ctx-demo-eu-02',
                batchId: 'batch-102',
                attemptId: 'attempt-102',
                startTime: '2026-04-20 08:31:05',
                completeTime: '2026-04-20 08:31:12',
                updateTime: '2026-04-20 08:31:12',
                errorCode: null,
                errorMessage: null,
                output: { status: 'ok', latencyMs: 721, value: 'warmed' },
            },
            {
                messageId: 'msg-003',
                eventCode: 'demo.dispatch',
                status: 'RUNNING',
                finalReason: null,
                retryCount: 1,
                maxRetryCount: 3,
                workerId: 'worker-demo-05',
                workerContextId: 'ctx-demo-apac-01',
                batchId: 'batch-109',
                attemptId: 'attempt-109',
                startTime: '2026-04-20 08:31:08',
                completeTime: '',
                updateTime: '2026-04-20 11:24:00',
                errorCode: null,
                errorMessage: null,
                output: null,
            },
        ],
        exports: {
            seedUrl: '/api/v1/tasks/task-001/review/seed-export',
            resultUrl: '/api/v1/tasks/task-001/review/result-export',
        },
    },
}

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listTasksMock(
    query: TaskListQuery = {},
): Promise<TaskListResponse> {
    const normalizedKeyword = query.keyword?.trim().toLowerCase() ?? ''
    const normalizedProject = query.project?.trim() ?? ''

    const filtered = mockTaskList.filter((task) => {
        const matchesKeyword =
            normalizedKeyword.length === 0 ||
            task.taskName.toLowerCase().includes(normalizedKeyword) ||
            task.id.toLowerCase().includes(normalizedKeyword)

        const matchesProject =
            normalizedProject.length === 0 || task.project === normalizedProject

        const matchesStatus = !query.status || task.status === query.status

        return matchesKeyword && matchesProject && matchesStatus
    })

    return delay({
        items: filtered,
        total: filtered.length,
    })
}

export async function getTaskDetailMock(
    taskId: string,
): Promise<TaskDetailResponse> {
    const detail = mockTaskDetails[taskId]

    if (!detail) {
        throw new Error(`Task detail not found for ${taskId}`)
    }

    return delay(detail)
}

export async function getTaskReviewMock(
    taskId: string,
): Promise<TaskReviewResponse> {
    const review = mockTaskReviews[taskId]

    if (!review) {
        throw new Error(`Task review not found for ${taskId}`)
    }

    return delay(review)
}

export async function createTaskShellMock(
    request: TaskShellCreateRequest,
): Promise<TaskShellCreateResult> {
    const taskId = `task-${String(mockTaskList.length + 1).padStart(3, '0')}`
    const createdAt = new Date().toISOString().slice(0, 19).replace('T', ' ')
    const normalizedSharedConfig = request.sharedConfig ?? {}
    const displayTaskName =
        request.sourceRef?.trim() || `${request.project}-${taskId}`
    const batchSize = request.executionSpec?.batchSize ?? request.batchSize ?? 1

    const listItem: TaskListItem = {
        id: taskId,
        taskName: displayTaskName,
        project: request.project,
        status: 'NEW',
        terminalReason: null,
        successCount: 0,
        eligibleCount: 0,
        batchSize,
        updatedAt: createdAt,
    }

    mockTaskList.unshift(listItem)
    mockTaskDetails[taskId] = {
        task: {
            tid: taskId,
            taskName: displayTaskName,
            project: request.project,
            status: 'NEW',
            terminalReason: null,
            batchSize,
            sharedConfig: normalizedSharedConfig,
            user: {
                name: request.userId,
            },
            taskTargetNumber: 0,
            taskEligibleNumber: 0,
            taskSuccessNumber: 0,
            taskNonSuccessNumber: 0,
            peakAssignedWorkerCount: 0,
            createTime: createdAt,
            updateTime: createdAt,
        },
    }
    mockTaskReviews[taskId] = {
        summary: {
            totalItems: 0,
            successItems: 0,
            failedItems: 0,
            expiredItems: 0,
            processingItems: 0,
            previewCount: 0,
            previewLimit: 12,
            hasMore: false,
        },
        seedPreview: [],
        resultPreview: [],
        exports: {
            seedUrl: `/api/v1/tasks/${taskId}/review/seed-export`,
            resultUrl: `/api/v1/tasks/${taskId}/review/result-export`,
        },
    }

    return delay({
        taskId,
        message: 'Task shell created',
    })
}

export async function appendTaskItemsMock(
    taskId: string,
    request: TaskItemBatchAppendRequest,
): Promise<{ added: number }> {
    const detail = mockTaskDetails[taskId]
    const listItem = mockTaskList.find((task) => task.id === taskId)
    const review = mockTaskReviews[taskId]
    const normalizedItems = request.items ?? []

    if (!detail || !listItem || !review) {
        throw new Error(`Task not found for ${taskId}`)
    }
    if (normalizedItems.length === 0) {
        throw new Error('items must contain at least one work item')
    }

    detail.task.taskTargetNumber += normalizedItems.length
    detail.task.taskEligibleNumber += normalizedItems.length
    detail.task.taskNonSuccessNumber += normalizedItems.length
    listItem.eligibleCount += normalizedItems.length

    const createdRows = normalizedItems.map((item, index) => {
        const messageId = `${taskId}-msg-${String(review.seedPreview.length + index + 1).padStart(3, '0')}`
        return {
            messageId,
            eventCode: request.eventCode ?? null,
            status: 'READY',
            payloadRef: null,
            retryCount: 0,
            maxRetryCount: 3,
            createTime: detail.task.updateTime,
            assignedTime: '',
            input: item,
        }
    })
    review.seedPreview = [...review.seedPreview, ...createdRows].slice(0, review.summary.previewLimit)
    review.resultPreview = [
        ...review.resultPreview,
        ...createdRows.map((row) => ({
            messageId: row.messageId,
            eventCode: row.eventCode,
            status: 'READY',
            finalReason: null,
            retryCount: 0,
            maxRetryCount: 3,
            workerId: null,
            workerContextId: null,
            batchId: null,
            attemptId: null,
            startTime: '',
            completeTime: '',
            updateTime: detail.task.updateTime,
            errorCode: null,
            errorMessage: null,
            output: null,
        })),
    ].slice(0, review.summary.previewLimit)
    review.summary.totalItems += normalizedItems.length
    review.summary.processingItems += normalizedItems.length
    review.summary.previewCount = review.seedPreview.length
    review.summary.hasMore = review.summary.totalItems > review.summary.previewLimit

    return delay({
        added: normalizedItems.length,
    })
}

export async function sealTaskMock(taskId: string): Promise<TaskActionResult> {
    const detail = mockTaskDetails[taskId]
    if (!detail) {
        throw new Error(`Task not found for ${taskId}`)
    }
    return delay({
        message: 'Task sealed',
    })
}

export async function invokeSyncTaskDebugMock(
    request: TaskDebugSyncRequest,
): Promise<TaskDebugSyncResult> {
    const created = await createTaskShellMock({
        userId: request.userId,
        project: request.project,
        sharedConfig: request.sharedConfig,
        executionSpec: {
            batchSize: request.batchSize ?? 1,
            maxRuntimeSeconds: request.maxRuntimeSeconds,
            workloadClass: request.workloadClass,
        },
        maxRuntimeSeconds: request.maxRuntimeSeconds,
    })
    await appendTaskItemsMock(created.taskId, {
        eventCode: request.eventCode,
        items: request.items,
    })
    await sealTaskMock(created.taskId)

    return delay({
        taskId: created.taskId,
        messageId: 'mock-message-001',
        synced: true,
        timedOut: false,
        status: 'SUCCESS',
        output: {},
        errorCode: '',
        errorMessage: '',
    })
}

export async function auditTaskMock(
    taskId: string,
    approved: boolean,
): Promise<TaskActionResult> {
    return updateTaskStatusMock(
        taskId,
        approved ? 'READY' : 'BLOCKED',
        approved ? 'Task approved' : 'Task rejected',
    )
}

export async function pauseTaskMock(taskId: string): Promise<TaskActionResult> {
    return updateTaskStatusMock(taskId, 'PAUSED', 'Task paused')
}

export async function resumeTaskMock(
    taskId: string,
): Promise<TaskActionResult> {
    return updateTaskStatusMock(taskId, 'READY', 'Task resumed')
}

export async function blockTaskMock(taskId: string): Promise<TaskActionResult> {
    return updateTaskStatusMock(taskId, 'BLOCKED', 'Task blocked')
}

export async function terminateTaskMock(
    taskId: string,
): Promise<TaskActionResult> {
    return updateTaskStatusMock(taskId, 'TERMINAL', 'Task terminated')
}

export function downloadTaskSeedExportMock(taskId: string): void {
    void taskId
    // Mock export is intentionally a no-op. The page still exposes the action.
}

export function downloadTaskResultExportMock(taskId: string): void {
    void taskId
    // Mock export is intentionally a no-op. The page still exposes the action.
}

async function updateTaskStatusMock(
    taskId: string,
    status: TaskListItem['status'],
    message: string,
): Promise<TaskActionResult> {
    const listItem = mockTaskList.find((task) => task.id === taskId)
    const detail = mockTaskDetails[taskId]

    if (!listItem || !detail) {
        throw new Error(`Task not found for ${taskId}`)
    }

    listItem.status = status
    detail.task.status = status
    if (status === 'TERMINAL') {
        listItem.terminalReason = 'MANUAL_CANCELLED'
        detail.task.terminalReason = 'MANUAL_CANCELLED'
    }

    return delay({
        message,
        newStatus: status,
        terminalReason: detail.task.terminalReason ?? undefined,
    })
}
