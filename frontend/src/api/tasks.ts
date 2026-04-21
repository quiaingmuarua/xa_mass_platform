import { getAppConfig } from '@/app/config'
import { getTaskDetailMock, listTasksMock } from '@/api/tasks.mock'
import { getTaskDetailReal, listTasksReal } from '@/api/tasks.real'
import type {
    TaskDetailResponse,
    TaskListQuery,
    TaskListResponse,
} from '@/types/tasks'

export async function listTasks(
    query: TaskListQuery = {},
): Promise<TaskListResponse> {
    if (getAppConfig().useMockApi) {
        return listTasksMock(query)
    }

    return listTasksReal(query)
}

export async function getTaskDetail(
    taskId: string,
): Promise<TaskDetailResponse> {
    if (getAppConfig().useMockApi) {
        return getTaskDetailMock(taskId)
    }

    return getTaskDetailReal(taskId)
}
