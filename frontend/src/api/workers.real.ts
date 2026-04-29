import {requestApiData} from '@/api/http'
import type {
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
