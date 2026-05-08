import {requestApiData} from '@/api/http'
import type {
    TaskActionResult,
    TaskDebugSyncRequest,
    TaskDebugSyncResult,
    TaskDetailRecord,
    TaskDetailResponse,
    TaskItemBatchAppendRequest,
    TaskListQuery,
    TaskListResponse,
    TaskShellCreateRequest,
    TaskShellCreateResult,
    TaskValidationSummary,
} from '@/types/tasks'

interface TaskDetailEnvelope {
    task: TaskDetailRecord
    stateValidation: TaskValidationSummary
}

export async function listTasksReal(
    query: TaskListQuery = {},
): Promise<TaskListResponse> {
    const searchParams = new URLSearchParams()

    if (query.keyword) {
        searchParams.set('keyword', query.keyword)
    }

    if (query.status) {
        searchParams.set('status', query.status)
    }

    const suffix = searchParams.size > 0 ? `?${searchParams.toString()}` : ''
    return requestApiData<TaskListResponse>(`/api/v1/tasks${suffix}`)
}

export async function createTaskShellReal(
    request: TaskShellCreateRequest,
): Promise<TaskShellCreateResult> {
    return requestApiData<TaskShellCreateResult>('/api/v1/tasks', {
        method: 'POST',
        body: JSON.stringify({
            userId: request.userId,
            project: request.project,
            taskName: request.taskName,
            eventCode: request.eventCode,
            mode: request.mode,
            payloadType: request.payloadType,
            sharedConfig: request.sharedConfig,
            batchSize: request.batchSize,
            maxRuntimeSeconds: request.maxRuntimeSeconds,
        }),
    })
}

export async function appendTaskItemsReal(
    taskId: string,
    request: TaskItemBatchAppendRequest,
): Promise<{ added: number }> {
    return requestApiData<{ added: number }>(`/api/v1/tasks/${taskId}/items`, {
        method: 'POST',
        body: JSON.stringify({
            items: request.items,
            defaultMsgMaxRetryCount: request.defaultMsgMaxRetryCount,
        }),
    })
}

export async function sealTaskReal(taskId: string): Promise<TaskActionResult> {
    return requestApiData<TaskActionResult>(`/api/v1/tasks/${taskId}:seal`, {
        method: 'POST',
    })
}

export async function invokeSyncTaskDebugReal(
    request: TaskDebugSyncRequest,
): Promise<TaskDebugSyncResult> {
    return requestApiData<TaskDebugSyncResult>(
        '/internal/v1/debug/task-invocations:sync',
        {
            method: 'POST',
            body: JSON.stringify({
                userId: request.userId,
                project: request.project,
                taskName: request.taskName,
                eventCode: request.eventCode,
                mode: 'SINGLE_RUN',
                payloadType: request.payloadType ?? 'JSON',
                sharedConfig: request.sharedConfig,
                inputs: request.inputs,
                batchSize: 1,
                defaultMsgMaxRetryCount: 0,
                maxRuntimeSeconds: request.maxRuntimeSeconds,
            }),
        },
    )
}

export async function getTaskDetailReal(
    taskId: string,
): Promise<TaskDetailResponse> {
    const detail = await requestApiData<TaskDetailEnvelope>(`/api/v1/tasks/${taskId}`)

    return {
        task: normalizeTaskRecord(detail.task),
        stateValidation: detail.stateValidation,
    }
}

export async function auditTaskReal(
    taskId: string,
    approved: boolean,
    comment = '',
): Promise<TaskActionResult> {
    const params = new URLSearchParams({
        approved: String(approved),
    })
    if (comment.trim().length > 0) {
        params.set('comment', comment.trim())
    }

    return requestApiData<TaskActionResult>(
        approved
            ? `/api/v1/tasks/${taskId}:approve?${params.toString()}`
            : `/api/v1/tasks/${taskId}:reject?${params.toString()}`,
        {
            method: 'POST',
        },
    )
}

export async function pauseTaskReal(taskId: string): Promise<TaskActionResult> {
    return invokeTaskActionReal(taskId, 'pause')
}

export async function resumeTaskReal(
    taskId: string,
): Promise<TaskActionResult> {
    return invokeTaskActionReal(taskId, 'resume')
}

export async function blockTaskReal(taskId: string): Promise<TaskActionResult> {
    return invokeTaskActionReal(taskId, 'block')
}

export async function terminateTaskReal(
    taskId: string,
): Promise<TaskActionResult> {
    return invokeTaskActionReal(taskId, 'terminate')
}

async function invokeTaskActionReal(
    taskId: string,
    action: 'pause' | 'resume' | 'block' | 'terminate',
): Promise<TaskActionResult> {
    return requestApiData<TaskActionResult>(
        `/api/v1/tasks/${taskId}:${action}`,
        {
            method: 'POST',
        },
    )
}

function normalizeTaskRecord(task: TaskDetailRecord): TaskDetailRecord {
    return {
        ...task,
        sharedConfig: task.sharedConfig ?? {},
        user: task.user ?? { name: '-' },
        createTime: normalizeDateTime(task.createTime),
        updateTime: normalizeDateTime(task.updateTime),
    }
}

function normalizeDateTime(value: unknown): string {
    if (typeof value === 'string') {
        return value
    }

    if (Array.isArray(value)) {
        const [year, month, day, hour = 0, minute = 0, second = 0] = value
        return `${year}-${padNumber(month)}-${padNumber(day)} ${padNumber(
            hour,
        )}:${padNumber(minute)}:${padNumber(second)}`
    }

    return value == null ? '' : String(value)
}

function padNumber(value: unknown): string {
    return String(value ?? 0).padStart(2, '0')
}
