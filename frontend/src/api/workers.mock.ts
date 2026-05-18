import type {WorkerListResponse} from '@/types/workers'

const mockWorkers: WorkerListResponse = {
    items: [
        {
            workerId: 'worker-us-01',
            status: 'ONLINE',
            workerGroupId: 'us-routing',
            agentVersion: '1.4.0',
            supportedProjects: ['demoApp'],
            supportedEventCodes: ['demo.dispatch', 'demo.dispatch.gb'],
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
            supportedProjects: ['demoApp', 'crawlerApp'],
            supportedEventCodes: ['demo.dispatch', 'crawler.fetch-page'],
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

function delay<T>(value: T): Promise<T> {
    return new Promise((resolve) => {
        window.setTimeout(() => resolve(value), 80)
    })
}

export async function listWorkersMock(): Promise<WorkerListResponse> {
    return delay(mockWorkers)
}
