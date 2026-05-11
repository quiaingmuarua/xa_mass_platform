import {setRuntimeConfigOverrides} from '@/app/config'
import {
    appendTaskItemsReal,
    createTaskShellReal,
    getTaskDetailReal,
    invokeSyncTaskDebugReal,
    listTasksReal,
    sealTaskReal,
    terminateTaskReal,
} from '@/api/tasks.real'

function jsonResponse(body: unknown): Response {
    return new Response(JSON.stringify(body), {
        status: 200,
        headers: {
            'Content-Type': 'application/json',
        },
    })
}

describe('tasks.real', () => {
    it('calls the backend task list endpoint with filters', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
        })
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    items: [
                        {
                            id: 'task-001',
                            taskName: 'Warm worker pool',
                            project: 'demoApp',
                            status: 'RUNNING',
                            terminalReason: null,
                            successCount: 6,
                            eligibleCount: 10,
                            batchSize: 2,
                            updatedAt: '2026-04-21 09:30:00',
                        },
                    ],
                    total: 1,
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const response = await listTasksReal({
            keyword: 'warm',
            project: 'demoApp',
            status: 'RUNNING',
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/tasks?keyword=warm&project=demoApp&status=RUNNING',
            expect.any(Object),
        )
        expect(response.total).toBe(1)
        expect(response.items[0].id).toBe('task-001')
    })

    it('loads task detail from the v1 task detail endpoint only', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    task: {
                        tid: 'task-001',
                        taskName: 'Warm worker pool',
                        project: 'demoApp',
                        status: 'RUNNING',
                        terminalReason: null,
                        batchSize: 2,
                        sharedConfig: {},
                        user: null,
                        taskTargetNumber: 10,
                        taskEligibleNumber: 10,
                        taskSuccessNumber: 6,
                        taskNonSuccessNumber: 4,
                        peakAssignedWorkerCount: 4,
                        createTime: [2026, 4, 21, 9, 0, 0],
                        updateTime: [2026, 4, 21, 9, 30, 0],
                    },
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const detail = await getTaskDetailReal('task-001')

        expect(fetchMock).toHaveBeenCalledTimes(1)
        expect(detail.task.user.name).toBe('-')
        expect(detail.task.createTime).toBe('2026-04-21 09:00:00')
    })

    it('posts task terminate to the backend action endpoint', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
        })
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    message: 'Task terminated',
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const response = await terminateTaskReal('task-001')

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/tasks/task-001:terminate',
            expect.objectContaining({
                method: 'POST',
            }),
        )
        expect(response.message).toBe('Task terminated')
    })

    it('creates a task shell through the v1 shell endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    taskId: 'task-101',
                    message: 'Task shell created',
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const result = await createTaskShellReal({
            userId: 'agent',
            project: 'demoApp',
            sharedConfig: { textContent: 'hello' },
            executionSpec: {
                batchSize: 2,
                maxRuntimeSeconds: 0,
            },
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/v1/tasks',
            expect.objectContaining({ method: 'POST' }),
        )
        expect(result.taskId).toBe('task-101')
    })

    it('appends items through the v1 item ingest endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    added: 2,
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const result = await appendTaskItemsReal('task-101', {
            eventCode: 'mock.state.get',
            items: [{ target: 'alpha' }, { target: 'beta' }],
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/v1/tasks/task-101/items',
            expect.objectContaining({ method: 'POST' }),
        )
        expect(result.added).toBe(2)
    })

    it('seals tasks through the v1 seal endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    message: 'Task sealed',
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const result = await sealTaskReal('task-101')

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/v1/tasks/task-101:seal',
            expect.objectContaining({ method: 'POST' }),
        )
        expect(result.message).toBe('Task sealed')
    })

    it('posts worker debug sync invocations to the internal v1 endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    taskId: 'task-debug-001',
                    messageId: 'msg-debug-001',
                    synced: true,
                    timedOut: false,
                    status: 'SUCCESS',
                    output: {},
                    errorCode: '',
                    errorMessage: '',
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const result = await invokeSyncTaskDebugReal({
            userId: 'ops-admin',
            project: 'demoApp',
            eventCode: 'mock.state.get',
            sharedConfig: {
                targetWorkerId: 'worker-us-01',
            },
            items: [
                {
                    includeRuntime: true,
                },
            ],
            maxRuntimeSeconds: 60,
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/internal/v1/debug/task-invocations:sync',
            expect.objectContaining({
                method: 'POST',
                body: JSON.stringify({
                    userId: 'ops-admin',
                    project: 'demoApp',
                    eventCode: 'mock.state.get',
                    sharedConfig: {
                        targetWorkerId: 'worker-us-01',
                    },
                    items: [
                        {
                            includeRuntime: true,
                        },
                    ],
                    batchSize: 1,
                    maxRuntimeSeconds: 60,
                }),
            }),
        )
        expect(result.taskId).toBe('task-debug-001')
        expect(result.synced).toBe(true)
    })
})
