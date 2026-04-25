import {getAppConfig} from '@/app/config'
import {
    listWorkerContextsMock,
    listWorkersMock,
    updateWorkerSupportedProjectsMock,
} from '@/api/workers.mock'
import {
    listWorkerContextsReal,
    listWorkersReal,
    updateWorkerSupportedProjectsReal,
} from '@/api/workers.real'
import type {
    WorkerContextListResponse,
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
