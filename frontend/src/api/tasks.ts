import { getAppConfig } from '@/app/config'
import {
    auditTaskMock,
    blockTaskMock,
    getTaskDetailMock,
    listTasksMock,
    pauseTaskMock,
    resumeTaskMock,
    terminateTaskMock,
} from '@/api/tasks.mock'
import { getTaskDetailReal, listTasksReal } from '@/api/tasks.real'
import {
    auditTaskReal,
    blockTaskReal,
    pauseTaskReal,
    resumeTaskReal,
    terminateTaskReal,
} from '@/api/tasks.real'
import type {
    TaskActionResult,
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

export async function auditTask(
    taskId: string,
    approved: boolean,
    comment = '',
): Promise<TaskActionResult> {
    if (getAppConfig().useMockApi) {
        return auditTaskMock(taskId, approved)
    }

    return auditTaskReal(taskId, approved, comment)
}

export async function pauseTask(taskId: string): Promise<TaskActionResult> {
    if (getAppConfig().useMockApi) {
        return pauseTaskMock(taskId)
    }

    return pauseTaskReal(taskId)
}

export async function resumeTask(taskId: string): Promise<TaskActionResult> {
    if (getAppConfig().useMockApi) {
        return resumeTaskMock(taskId)
    }

    return resumeTaskReal(taskId)
}

export async function blockTask(taskId: string): Promise<TaskActionResult> {
    if (getAppConfig().useMockApi) {
        return blockTaskMock(taskId)
    }

    return blockTaskReal(taskId)
}

export async function terminateTask(
    taskId: string,
): Promise<TaskActionResult> {
    if (getAppConfig().useMockApi) {
        return terminateTaskMock(taskId)
    }

    return terminateTaskReal(taskId)
}
