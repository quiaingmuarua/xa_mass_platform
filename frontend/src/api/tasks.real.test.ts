import {setRuntimeConfigOverrides} from '@/app/config'
import {createTaskReal, getTaskDetailReal, listTasksReal, terminateTaskReal,} from '@/api/tasks.real'

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
            status: 'RUNNING',
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/api/v1/tasks?keyword=warm&status=RUNNING',
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
                    stateValidation: {
                        valid: true,
                        needsResolution: false,
                        totalMessages: 10,
                        successMessages: 6,
                        failedMessages: 0,
                        processingMessages: 4,
                        violations: [],
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

    it('creates a task by orchestrating shell create, append, and seal', async () => {
        const fetchMock = vi.fn()
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        taskId: 'task-101',
                        message: 'Task shell created',
                    },
                }),
            )
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        added: 2,
                    },
                }),
            )
            .mockResolvedValueOnce(
                jsonResponse({
                    code: 0,
                    msg: 'ok',
                    data: {
                        message: 'Task sealed',
                    },
                }),
            )
        vi.stubGlobal('fetch', fetchMock)

        const result = await createTaskReal({
            userId: 'agent',
            project: 'demoApp',
            taskName: 'demo-task',
            sharedConfig: { textContent: 'hello' },
            inputs: [{ target: 'alpha' }, { target: 'beta' }],
            batchSize: 2,
            defaultMsgMaxRetryCount: 3,
            openEnded: false,
            maxRuntimeSeconds: 0,
        })

        expect(fetchMock).toHaveBeenNthCalledWith(
            1,
            '/api/v1/tasks',
            expect.objectContaining({ method: 'POST' }),
        )
        expect(fetchMock).toHaveBeenNthCalledWith(
            2,
            '/api/v1/tasks/task-101/items',
            expect.objectContaining({ method: 'POST' }),
        )
        expect(fetchMock).toHaveBeenNthCalledWith(
            3,
            '/api/v1/tasks/task-101:seal',
            expect.objectContaining({ method: 'POST' }),
        )
        expect(result.taskId).toBe('task-101')
    })
})
