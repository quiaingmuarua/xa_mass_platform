import { requestJson } from '@/api/http'
import type {
    TaskActionResult,
    TaskDetailResponse,
    TaskDetailRecord,
    TaskListQuery,
    TaskListResponse,
    TaskMessageView,
    TaskValidationSummary,
} from '@/types/tasks'

interface LegacySuccessEnvelope {
    success: boolean
    message?: string
}

interface TaskDetailEnvelope extends LegacySuccessEnvelope {
    task: TaskDetailRecord
    items: Array<Record<string, unknown>>
    stateValidation: TaskValidationSummary
}

interface TaskMessagesEnvelope extends LegacySuccessEnvelope {
    messages: TaskMessageView[]
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
    return unwrapLegacyResponse(
        await requestJson<TaskListResponse & LegacySuccessEnvelope>(
            `/status/api/tasks${suffix}`,
        ),
    )
}

export async function getTaskDetailReal(
    taskId: string,
): Promise<TaskDetailResponse> {
    const [detail, messages] = await Promise.all([
        requestJson<TaskDetailEnvelope>(`/status/api/tasks/${taskId}`),
        requestJson<TaskMessagesEnvelope>(
            `/status/api/tasks/${taskId}/messages?page=1&size=200`,
        ),
    ])

    const unwrappedDetail = unwrapLegacyResponse(detail)
    const unwrappedMessages = unwrapLegacyResponse(messages)

    return {
        task: normalizeTaskRecord(unwrappedDetail.task),
        items: unwrappedDetail.items ?? [],
        stateValidation: unwrappedDetail.stateValidation,
        messages: unwrappedMessages.messages ?? [],
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

    return unwrapLegacyResponse(
        await requestJson<TaskActionResult & LegacySuccessEnvelope>(
            `/status/api/tasks/${taskId}/audit?${params.toString()}`,
            {
                method: 'POST',
            },
        ),
    )
}

export async function pauseTaskReal(taskId: string): Promise<TaskActionResult> {
    return invokeTaskActionReal(taskId, 'pause')
}

export async function resumeTaskReal(taskId: string): Promise<TaskActionResult> {
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
    return unwrapLegacyResponse(
        await requestJson<TaskActionResult & LegacySuccessEnvelope>(
            `/status/api/tasks/${taskId}/${action}`,
            {
                method: 'POST',
            },
        ),
    )
}

function unwrapLegacyResponse<T extends LegacySuccessEnvelope>(payload: T): T {
    if (!payload.success) {
        throw new Error(payload.message ?? 'Backend request failed.')
    }

    return payload
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
