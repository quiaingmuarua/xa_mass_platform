import {getAppConfig} from '@/app/config'
import {
    getWorkerDebugHistoryMock,
    listWorkerContextsMock,
    listWorkersMock,
    sendWorkerDebugMessageMock,
    updateWorkerSupportedProjectsMock,
} from '@/api/workers.mock'
import {
    getWorkerDebugHistoryReal,
    listWorkerContextsReal,
    listWorkersReal,
    sendWorkerDebugMessageReal,
    updateWorkerSupportedProjectsReal,
} from '@/api/workers.real'
import type {
    WorkerContextListResponse,
    WorkerDebugHistoryResponse,
    WorkerDebugSendRequest,
    WorkerDebugSendResult,
    WorkerListResponse,
} from '@/types/workers'

export async function listWorkers(): Promise<WorkerListResponse> {
    if (getAppConfig().useMockApi) {
        return listWorkersMock()
    }

    return listWorkersReal()
}

export async function listWorkerContexts(): Promise<WorkerContextListResponse> {
    if (getAppConfig().useMockApi) {
        return listWorkerContextsMock()
    }

    return listWorkerContextsReal()
}

export async function updateWorkerSupportedProjects(
    workerId: string,
    supportedProjects: string[],
): Promise<void> {
    if (getAppConfig().useMockApi) {
        return updateWorkerSupportedProjectsMock(workerId, supportedProjects)
    }

    return updateWorkerSupportedProjectsReal(workerId, supportedProjects)
}

export async function getWorkerDebugHistory(
    workerId: string,
): Promise<WorkerDebugHistoryResponse> {
    if (getAppConfig().useMockApi) {
        return getWorkerDebugHistoryMock(workerId)
    }

    return getWorkerDebugHistoryReal(workerId)
}

export async function sendWorkerDebugMessage(
    request: WorkerDebugSendRequest,
): Promise<WorkerDebugSendResult> {
    if (getAppConfig().useMockApi) {
        return sendWorkerDebugMessageMock(request)
    }

    return sendWorkerDebugMessageReal(request)
}
