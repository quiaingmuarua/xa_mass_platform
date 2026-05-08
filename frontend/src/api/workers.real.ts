import {requestApiData} from '@/api/http'
import type {
    WorkerContextListResponse,
    WorkerListResponse,
} from '@/types/workers'

export async function listWorkersReal(): Promise<WorkerListResponse> {
    return requestApiData<WorkerListResponse>('/api/v1/runtime/workers')
}

export async function listWorkerContextsReal(): Promise<WorkerContextListResponse> {
    return requestApiData<WorkerContextListResponse>(
        '/api/v1/runtime/worker-contexts',
    )
}
