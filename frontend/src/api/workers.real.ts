import {requestApiData} from '@/api/http'
import type {WorkerListResponse} from '@/types/workers'

export async function listWorkersReal(): Promise<WorkerListResponse> {
    return requestApiData<WorkerListResponse>('/api/v1/runtime/workers')
}
