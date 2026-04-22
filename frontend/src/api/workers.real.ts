import { requestApiData } from '@/api/http'
import type {
    WorkerDebugHistoryResponse,
    WorkerDebugSendRequest,
    WorkerDebugSendResult,
    WorkerContextListResponse,
    WorkerListResponse,
} from '@/types/workers'

export async function listWorkersReal(): Promise<WorkerListResponse> {
    return requestApiData<WorkerListResponse>('/status/api/workers')
}

export async function listWorkerContextsReal(): Promise<WorkerContextListResponse> {
    return requestApiData<WorkerContextListResponse>(
        '/status/api/worker-contexts',
    )
}

export async function updateWorkerSupportedProjectsReal(
    workerId: string,
    supportedProjects: string[],
): Promise<void> {
    await requestApiData(`/status/api/workers/${workerId}/supported-projects`, {
        method: 'PUT',
        body: JSON.stringify({ supportedProjects }),
    })
}

export async function getWorkerDebugHistoryReal(
    workerId: string,
): Promise<WorkerDebugHistoryResponse> {
    return requestApiData<WorkerDebugHistoryResponse>(
        `/status/workers/message-history?workerId=${encodeURIComponent(workerId)}`,
    )
}

export async function sendWorkerDebugMessageReal(
    request: WorkerDebugSendRequest,
): Promise<WorkerDebugSendResult> {
    return requestApiData<WorkerDebugSendResult>(
        '/status/workers/send-message',
        {
            method: 'POST',
            body: JSON.stringify(request),
        },
    )
}
