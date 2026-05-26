import type {WorkerListResponse} from '@/types/workers'

const mockWorkers: WorkerListResponse = {
    items: [
        {
            workerId: 'public-probe-http-poll-use1-001',
            status: 'ONLINE',
            transportReachability: 'ONLINE',
            transportOnline: true,
            workerGroupId: 'public-probe-http',
            adapterNodeId: 'control-console-polling',
            transportHint: 'polling',
            agentVersion: '1.4.0',
            supportedProjects: ['publicProbe'],
            supportedEventCodes: ['probe.weather.current', 'probe.fx.latest', 'probe.http.status'],
            attributes: {
                region: 'use1',
                executionProfile: 'public-http',
            },
            lastHeartbeat: '2026-04-21 09:45:00',
            locked: true,
            updateTime: '2026-04-21 09:45:00',
        },
        {
            workerId: 'phone-device-probe-ws-sg-001',
            status: 'ONLINE',
            transportReachability: 'ONLINE',
            transportOnline: true,
            workerGroupId: 'phone-device-probe',
            adapterNodeId: 'control-console-websocket',
            transportHint: 'realtime',
            agentVersion: '1.3.7',
            supportedProjects: ['deviceProbe'],
            supportedEventCodes: ['probe.phone.metadata'],
            attributes: {
                country: 'SG',
                fingerprintProfile: 'fp-android-sg-a',
                fingerprintHash: 'sha256:fp-a',
                deviceModel: 'Pixel-7a',
                networkOperatorMccMnc: '52501',
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
