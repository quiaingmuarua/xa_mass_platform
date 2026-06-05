import {setRuntimeConfigOverrides} from '@/app/config'
import {
    appendTaskItemsReal,
    createTaskShellReal,
    getTaskDetailReal,
    getTaskReviewReal,
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
                            fieldSources: {
                                taskId: 'controlPlaneShell',
                                status: 'runtimeCurrent',
                                successCount: 'compatibilityAlias',
                            },
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
        expect(response.items[0].fieldSources?.status).toBe('runtimeCurrent')
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
                        fieldSources: {
                            taskId: 'controlPlaneShell',
                            status: 'runtimeCurrent',
                        },
                    },
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const detail = await getTaskDetailReal('task-001')

        expect(fetchMock).toHaveBeenCalledTimes(1)
        expect(detail.task.user.name).toBe('-')
        expect(detail.task.createTime).toBe('2026-04-21 09:00:00')
        expect(detail.task.fieldSources?.taskId).toBe('controlPlaneShell')
    })

    it('loads task review preview from the explicit review endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    summary: {
                        totalItems: 10,
                        successItems: 6,
                        failedItems: 1,
                        expiredItems: 0,
                        processingItems: 3,
                        previewCount: 2,
                        previewLimit: 12,
                        hasMore: true,
                    },
                    seedPreview: [],
                    resultPreview: [],
                    exports: {
                        seedUrl: '/internal/v1/review/tasks/task-001/seed-export',
                        resultUrl: '/internal/v1/review/tasks/task-001/result-export',
                    },
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const review = await getTaskReviewReal('task-001')

        expect(fetchMock).toHaveBeenCalledWith(
            '/internal/v1/review/tasks/task-001',
            expect.any(Object),
        )
        expect(review.summary.totalItems).toBe(10)
        expect(review.exports.resultUrl).toBe(
            '/internal/v1/review/tasks/task-001/result-export',
        )
    })

    it('posts task terminate through the unified command endpoint', async () => {
        setRuntimeConfigOverrides({
            apiBaseUrl: '/backend',
        })
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    taskId: 'task-001',
                    command: 'TERMINATE',
                    accepted: true,
                    status: 'TERMINAL',
                    terminalReason: 'MANUAL_CANCELLED',
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const response = await terminateTaskReal('task-001')

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/tasks/task-001/commands',
            expect.objectContaining({
                method: 'POST',
                body: JSON.stringify({
                    command: 'TERMINATE',
                    reason: undefined,
                }),
            }),
        )
        expect(response.message).toBe('Task command TERMINATE accepted')
        expect(response.newStatus).toBe('TERMINAL')
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

    it('seals tasks through the unified command endpoint', async () => {
        const fetchMock = vi.fn().mockResolvedValue(
            jsonResponse({
                code: 0,
                msg: 'ok',
                data: {
                    taskId: 'task-101',
                    command: 'SEAL',
                    accepted: true,
                    status: 'READY',
                },
            }),
        )
        vi.stubGlobal('fetch', fetchMock)

        const result = await sealTaskReal('task-101')

        expect(fetchMock).toHaveBeenCalledWith(
            '/api/v1/tasks/task-101/commands',
            expect.objectContaining({
                method: 'POST',
                body: JSON.stringify({
                    command: 'SEAL',
                    reason: undefined,
                }),
            }),
        )
        expect(result.message).toBe('Task command SEAL accepted')
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
