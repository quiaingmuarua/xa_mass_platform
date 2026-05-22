import {getAppConfig} from '@/app/config'
import {
    auditTaskMock,
    appendTaskItemsMock,
    blockTaskMock,
    createTaskShellMock,
    getTaskDetailMock,
    getTaskReviewMock,
    invokeSyncTaskDebugMock,
    listTasksMock,
    pauseTaskMock,
    resumeTaskMock,
    sealTaskMock,
    terminateTaskMock,
    downloadTaskResultExportMock,
    downloadTaskSeedExportMock,
} from '@/api/tasks.mock'
import {
    auditTaskReal,
    appendTaskItemsReal,
    blockTaskReal,
    createTaskShellReal,
    downloadTaskResultExportReal,
    downloadTaskSeedExportReal,
    getTaskDetailReal,
    getTaskReviewReal,
    invokeSyncTaskDebugReal,
    listTasksReal,
    pauseTaskReal,
    resumeTaskReal,
    sealTaskReal,
    terminateTaskReal,
} from '@/api/tasks.real'
import type {
    TaskActionResult,
    TaskDebugSyncRequest,
    TaskDebugSyncResult,
    TaskDetailResponse,
    TaskItemBatchAppendRequest,
    TaskListQuery,
    TaskListResponse,
    TaskReviewResponse,
    TaskShellCreateRequest,
    TaskShellCreateResult,
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

export async function getTaskReview(
    taskId: string,
): Promise<TaskReviewResponse> {
    if (getAppConfig().useMockApi) {
        return getTaskReviewMock(taskId)
    }

    return getTaskReviewReal(taskId)
}

export async function createTaskShell(
    request: TaskShellCreateRequest,
): Promise<TaskShellCreateResult> {
    if (getAppConfig().useMockApi) {
        return createTaskShellMock(request)
    }

    return createTaskShellReal(request)
}

export async function appendTaskItems(
    taskId: string,
    request: TaskItemBatchAppendRequest,
): Promise<{ added: number }> {
    if (getAppConfig().useMockApi) {
        return appendTaskItemsMock(taskId, request)
    }

    return appendTaskItemsReal(taskId, request)
}

export async function sealTask(
    taskId: string,
): Promise<TaskActionResult> {
    if (getAppConfig().useMockApi) {
        return sealTaskMock(taskId)
    }

    return sealTaskReal(taskId)
}

export async function invokeSyncTaskDebug(
    request: TaskDebugSyncRequest,
): Promise<TaskDebugSyncResult> {
    if (getAppConfig().useMockApi) {
        return invokeSyncTaskDebugMock(request)
    }

    return invokeSyncTaskDebugReal(request)
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

export async function terminateTask(taskId: string): Promise<TaskActionResult> {
    if (getAppConfig().useMockApi) {
        return terminateTaskMock(taskId)
    }

    return terminateTaskReal(taskId)
}

export function downloadTaskSeedExport(taskId: string): void {
    if (getAppConfig().useMockApi) {
        downloadTaskSeedExportMock(taskId)
        return
    }

    downloadTaskSeedExportReal(taskId)
}

export function downloadTaskResultExport(taskId: string): void {
    if (getAppConfig().useMockApi) {
        downloadTaskResultExportMock(taskId)
        return
    }

    downloadTaskResultExportReal(taskId)
}
