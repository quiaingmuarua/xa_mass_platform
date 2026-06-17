import {buildApiUrl, requestApiData, triggerDownload} from '@/api/http'
import type {
    TaskActionResult,
    TaskDebugSyncRequest,
    TaskDebugSyncResult,
    TaskDetailRecord,
    TaskDetailResponse,
    TaskItemBatchAppendRequest,
    TaskListQuery,
    TaskListResponse,
    TaskResultWindowResponse,
    TaskReviewResponse,
    TaskShellCreateRequest,
    TaskShellCreateResult,
} from '@/types/tasks'

interface TaskDetailEnvelope {
    task: TaskDetailRecord
}

interface TaskCommandOutcome {
    taskId: string
    command: string
    accepted: boolean
    status?: TaskActionResult['newStatus']
    intakeStatus?: TaskActionResult['intakeStatus']
    terminalReason?: string
    failureReason?: string
}

export async function listTasksReal(
    query: TaskListQuery = {},
): Promise<TaskListResponse> {
    const searchParams = new URLSearchParams()

    if (query.keyword) {
        searchParams.set('keyword', query.keyword)
    }

    if (query.project) {
        searchParams.set('project', query.project)
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
    const executionSpec = request.executionSpec ?? {
        batchSize: request.batchSize,
        maxRuntimeSeconds: request.maxRuntimeSeconds,
    }
    return requestApiData<TaskShellCreateResult>('/api/v1/tasks', {
        method: 'POST',
        body: JSON.stringify({
            userId: request.userId,
            project: request.project,
            sharedConfig: request.sharedConfig,
            executionSpec,
            sourceType: request.sourceType,
            sourceRef: request.sourceRef,
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
            eventCode: request.eventCode,
            items: request.items,
        }),
    })
}

export async function sealTaskReal(taskId: string): Promise<TaskActionResult> {
    return executeTaskCommandReal(taskId, 'SEAL')
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
                eventCode: request.eventCode,
                sharedConfig: request.sharedConfig,
                items: request.items,
                batchSize: request.batchSize ?? 1,
                maxRuntimeSeconds: request.maxRuntimeSeconds,
                workloadClass: request.workloadClass,
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
    }
}

export async function getTaskReviewReal(
    taskId: string,
): Promise<TaskReviewResponse> {
    return requestApiData<TaskReviewResponse>(`/internal/v1/review/tasks/${taskId}`)
}

export async function getTaskResultsReal(
    taskId: string,
    limit = 12,
): Promise<TaskResultWindowResponse> {
    const searchParams = new URLSearchParams()
    searchParams.set('limit', String(Math.max(1, limit)))
    return requestApiData<TaskResultWindowResponse>(
        `/api/v1/tasks/${taskId}/results?${searchParams.toString()}`,
    )
}

export async function auditTaskReal(
    taskId: string,
    approved: boolean,
    comment = '',
): Promise<TaskActionResult> {
    return executeTaskCommandReal(
        taskId,
        approved ? 'APPROVE' : 'REJECT',
        comment,
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

export function downloadTaskSeedExportReal(taskId: string): void {
    triggerDownload(buildApiUrl(`/internal/v1/review/tasks/${taskId}/seed-export`))
}

export function downloadTaskResultExportReal(taskId: string): void {
    triggerDownload(buildApiUrl(`/internal/v1/review/tasks/${taskId}/result-export`))
}

async function invokeTaskActionReal(
    taskId: string,
    action: 'pause' | 'resume' | 'block' | 'terminate',
): Promise<TaskActionResult> {
    return executeTaskCommandReal(taskId, action.toUpperCase())
}

async function executeTaskCommandReal(
    taskId: string,
    command: string,
    reason = '',
): Promise<TaskActionResult> {
    const outcome = await requestApiData<TaskCommandOutcome>(
        `/api/v1/tasks/${taskId}/commands`,
        {
            method: 'POST',
            body: JSON.stringify({
                command,
                reason: reason.trim() || undefined,
            }),
        },
    )

    return {
        message: outcome.accepted
            ? `Task command ${outcome.command} accepted`
            : (outcome.failureReason ?? `Task command ${outcome.command} rejected`),
        newStatus: outcome.status,
        intakeStatus: outcome.intakeStatus,
        terminalReason: outcome.terminalReason,
    }
}

function normalizeTaskRecord(task: TaskDetailRecord): TaskDetailRecord {
    return {
        ...task,
        intakeStatus: task.intakeStatus ?? null,
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
