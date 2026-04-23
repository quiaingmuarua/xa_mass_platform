import type {
    TaskActionResult,
    TaskCreateRequest,
    TaskCreateResult,
    TaskDetailResponse,
    TaskListItem,
    TaskListQuery,
    TaskListResponse,
} from '@/types/tasks'

const mockTaskList: TaskListItem[] = [
    {
        id: 'task-001',
        taskName: 'Warm worker pool for us-routing',
        project: 'demoApp',
        routingCode: 'us',
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
        routingCode: 'sg',
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
        routingCode: 'eu',
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
            taskName: 'Warm worker pool for us-routing',
            project: 'demoApp',
            status: 'RUNNING',
            terminalReason: null,
            batchSize: 2,
            sharedConfig: {
                objective: 'stabilize dispatch throughput',
                targetConcurrency: 4,
                routingCode: 'us',
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
        items: [
            { target: 'worker-us-01' },
            { target: 'worker-us-02' },
            { target: 'worker-us-03' },
            { target: 'worker-us-04' },
        ],
        stateValidation: {
            valid: true,
            needsResolution: false,
            totalMessages: 10,
            successMessages: 6,
            failedMessages: 0,
            processingMessages: 4,
            violations: [],
        },
        messages: [
            {
                msgId: 'msg-001',
                status: 'SUCCESS',
                latestAttemptWorkerId: 'worker-us-01',
                latestAttemptWorkerContextId: 'ctx-us-01',
                latestAttemptBatchId: 'batch-01',
                retryCount: 0,
                maxRetryCount: 3,
                finalReason: 'BUSINESS_SUCCESS',
                input: { target: 'worker-us-01' },
                output: { result: 'ready' },
                errorMessage: null,
            },
            {
                msgId: 'msg-004',
                status: 'RUNNING',
                latestAttemptWorkerId: 'worker-us-04',
                latestAttemptWorkerContextId: 'ctx-us-04',
                latestAttemptBatchId: 'batch-02',
                retryCount: 1,
                maxRetryCount: 3,
                finalReason: null,
                input: { target: 'worker-us-04' },
                output: {},
                errorMessage: null,
            },
        ],
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
                routingCode: 'sg',
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
        items: [{ target: 'delivery-01' }, { target: 'delivery-02' }],
        stateValidation: {
            valid: true,
            needsResolution: false,
            totalMessages: 8,
            successMessages: 2,
            failedMessages: 2,
            processingMessages: 0,
            violations: [],
        },
        messages: [],
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
                routingCode: 'eu',
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
        items: [{ target: 'session-01' }],
        stateValidation: {
            valid: true,
            needsResolution: false,
            totalMessages: 12,
            successMessages: 12,
            failedMessages: 0,
            processingMessages: 0,
            violations: [],
        },
        messages: [],
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

    const filtered = mockTaskList.filter((task) => {
        const matchesKeyword =
            normalizedKeyword.length === 0 ||
            task.taskName.toLowerCase().includes(normalizedKeyword) ||
            task.id.toLowerCase().includes(normalizedKeyword)

        const matchesStatus = !query.status || task.status === query.status

        return matchesKeyword && matchesStatus
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

export async function createTaskMock(
    request: TaskCreateRequest,
): Promise<TaskCreateResult> {
    const taskId = `task-${String(mockTaskList.length + 1).padStart(3, '0')}`
    const createdAt = new Date().toISOString().slice(0, 19).replace('T', ' ')
    const normalizedSharedConfig = request.sharedConfig ?? {}
    const normalizedInputs = request.inputs ?? []
    const routingCode =
        typeof normalizedSharedConfig.routingCode === 'string'
            ? normalizedSharedConfig.routingCode
            : ''

    if (normalizedInputs.length === 0) {
        throw new Error('inputs must contain at least one work item')
    }

    const listItem: TaskListItem = {
        id: taskId,
        taskName: request.taskName,
        project: request.project,
        routingCode,
        status: 'NEW',
        terminalReason: null,
        successCount: 0,
        eligibleCount: normalizedInputs.length,
        batchSize: request.batchSize,
        updatedAt: createdAt,
    }

    mockTaskList.unshift(listItem)
    mockTaskDetails[taskId] = {
        task: {
            tid: taskId,
            taskName: request.taskName,
            project: request.project,
            status: 'NEW',
            terminalReason: null,
            batchSize: request.batchSize,
            sharedConfig: normalizedSharedConfig,
            user: {
                name: request.userId,
            },
            taskTargetNumber: normalizedInputs.length,
            taskEligibleNumber: normalizedInputs.length,
            taskSuccessNumber: 0,
            taskNonSuccessNumber: normalizedInputs.length,
            peakAssignedWorkerCount: 0,
            createTime: createdAt,
            updateTime: createdAt,
        },
        items: normalizedInputs,
        stateValidation: {
            valid: true,
            needsResolution: false,
            totalMessages: normalizedInputs.length,
            successMessages: 0,
            failedMessages: 0,
            processingMessages: 0,
            violations: [],
        },
        messages: normalizedInputs.map((input, index) => ({
            msgId: `${taskId}-msg-${index + 1}`,
            status: 'INIT',
            latestAttemptWorkerId: null,
            latestAttemptWorkerContextId: null,
            latestAttemptBatchId: null,
            retryCount: 0,
            maxRetryCount: request.defaultMsgMaxRetryCount,
            finalReason: null,
            input,
            output: {},
            errorMessage: null,
        })),
    }

    return delay({
        taskId,
        message: 'Task created',
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
