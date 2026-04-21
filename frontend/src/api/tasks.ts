import { appConfig } from '@/app/config'
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
    if (appConfig.useMockApi) {
        return listTasksMock(query)
    }

    return listTasksReal(query)
}

export async function getTaskDetail(
    taskId: string,
): Promise<TaskDetailResponse> {
    if (appConfig.useMockApi) {
        return getTaskDetailMock(taskId)
    }

    return getTaskDetailReal(taskId)
}
