import type {
    WorkerContextListResponse,
    WorkerListResponse,
} from '@/types/workers'

const mockWorkers: WorkerListResponse = {
    items: [
        {
            workerId: 'worker-us-01',
            status: 'ONLINE',
            workerGroupId: 'us-routing',
            agentVersion: '1.4.0',
            supportedProjects: ['demoApp'],
            attributes: {
                region: 'us',
                lane: 'primary',
            },
            lastHeartbeat: '2026-04-21 09:45:00',
            locked: true,
            updateTime: '2026-04-21 09:45:00',
        },
        {
            workerId: 'worker-sg-01',
            status: 'OFFLINE',
            workerGroupId: 'sg-routing',
            agentVersion: '1.3.7',
            supportedProjects: ['demoApp', 'telegramApp'],
            attributes: {
                region: 'sg',
            },
            lastHeartbeat: '2026-04-21 08:12:00',
            locked: false,
            updateTime: '2026-04-21 08:18:00',
        },
    ],
    total: 2,
}

const mockWorkerContexts: WorkerContextListResponse = {
    items: [
        {
            workerContextId: 'ctx-us-01',
            workerId: 'worker-us-01',
            status: 'OCCUPIED',
            channel: 'telegram',
            attributes: {
                account: 'ops-us-a',
            },
            lastBindTaskId: 'task-001',
            lastUsedTime: '2026-04-21 09:44:00',
            updateTime: '2026-04-21 09:44:00',
        },
        {
            workerContextId: 'ctx-sg-01',
            workerId: 'worker-sg-01',
            status: 'IDLE',
            channel: 'telegram',
            attributes: {
                account: 'ops-sg-a',
            },
            lastBindTaskId: null,
            lastUsedTime: '',
            updateTime: '2026-04-21 08:00:00',
        },
    ],
    total: 2,
}

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listWorkersMock(): Promise<WorkerListResponse> {
    return delay(mockWorkers)
}

export async function listWorkerContextsMock(): Promise<WorkerContextListResponse> {
    return delay(mockWorkerContexts)
}

export async function updateWorkerSupportedProjectsMock(
    workerId: string,
    supportedProjects: string[],
): Promise<void> {
    const worker = mockWorkers.items.find((item) => item.workerId === workerId)
    if (!worker) {
        throw new Error(`Worker not found: ${workerId}`)
    }

    worker.supportedProjects = supportedProjects
    await delay(undefined)
}
