import { requestJson } from '@/api/http'
import type {
    TaskDetailResponse,
    TaskListQuery,
    TaskListResponse,
} from '@/types/tasks'

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
    return requestJson<TaskListResponse>(`/tasks${suffix}`)
}

export async function getTaskDetailReal(
    taskId: string,
): Promise<TaskDetailResponse> {
    return requestJson<TaskDetailResponse>(`/tasks/${taskId}`)
}
