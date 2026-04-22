import type {
    WorkerDebugHistoryResponse,
    WorkerDebugMessageRecord,
    WorkerDebugSendRequest,
    WorkerDebugSendResult,
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

const mockDebugHistoryByWorker = new Map<string, WorkerDebugMessageRecord[]>()

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

export async function getWorkerDebugHistoryMock(
    workerId: string,
): Promise<WorkerDebugHistoryResponse> {
    return delay({
        workerId,
        items: [...(mockDebugHistoryByWorker.get(workerId) ?? [])],
    })
}

export async function sendWorkerDebugMessageMock(
    request: WorkerDebugSendRequest,
): Promise<WorkerDebugSendResult> {
    const worker = mockWorkers.items.find(
        (item) => item.workerId === request.workerId,
    )
    if (!worker) {
        throw new Error(`Worker not found: ${request.workerId}`)
    }
    if (worker.status !== 'ONLINE') {
        throw new Error('Target worker is offline or task_messages session is unavailable')
    }

    const project = request.project || worker.supportedProjects[0] || 'demoApp'
    const msgType = request.msgType || 'CONTROL'
    const subMsgType = request.subMsgType || 'manual-chat'
    const messageId = `mock-debug-${Date.now()}`
    const now = Date.now()

    appendDebugRecord(request.workerId, {
        messageId,
        replyToMessageId: null,
        workerId: request.workerId,
        direction: 'OUTBOUND',
        project,
        msgType,
        subMsgType,
        status: 'DELIVERED',
        payloadJson: prettyJson(request.payload),
        rawJson: prettyJson({
            workerId: request.workerId,
            project,
            msgType,
            subMsgType,
            payload: request.payload,
        }),
        detail: 'mock worker received manual debug message',
        createdAt: now,
        updatedAt: now,
    })

    appendDebugRecord(request.workerId, {
        messageId: `${messageId}-ack`,
        replyToMessageId: messageId,
        workerId: request.workerId,
        direction: 'INBOUND',
        project,
        msgType: 'EVENT',
        subMsgType,
        status: 'RECEIVED',
        payloadJson: prettyJson({
            messageKind: 'debug_chat_ack',
            replyToMessageId: messageId,
            ackStatus: 'RECEIVED',
            text:
                typeof request.payload.text === 'string'
                    ? request.payload.text
                    : 'mock worker received manual debug message',
            workerId: request.workerId,
        }),
        rawJson: prettyJson({
            workerId: request.workerId,
            project,
            msgType: 'EVENT',
            subMsgType,
        }),
        detail: 'mock worker acked manual debug message',
        createdAt: now + 1,
        updatedAt: now + 1,
    })

    return delay({
        messageId,
        workerId: request.workerId,
        project,
        msgType,
        subMsgType,
    })
}

function appendDebugRecord(
    workerId: string,
    record: WorkerDebugMessageRecord,
): void {
    const items = mockDebugHistoryByWorker.get(workerId) ?? []
    items.push(record)
    mockDebugHistoryByWorker.set(workerId, items.slice(-120))
}

function prettyJson(value: unknown): string {
    return JSON.stringify(value ?? {}, null, 2)
}
