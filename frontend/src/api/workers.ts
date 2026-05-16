import {getAppConfig} from '@/app/config'
import {listWorkersMock} from '@/api/workers.mock'
import {listWorkersReal} from '@/api/workers.real'
import type {WorkerListResponse} from '@/types/workers'

export async function listWorkers(): Promise<WorkerListResponse> {
    if (getAppConfig().useMockApi) {
        return listWorkersMock()
    }

    return listWorkersReal()
}
