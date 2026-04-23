import { setRuntimeConfigOverrides } from '@/app/config'
import {
    getTaskDetailReal,
    listTasksReal,
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
            status: 'RUNNING',
        })

        expect(fetchMock).toHaveBeenCalledWith(
            '/backend/status/api/tasks?keyword=warm&status=RUNNING',
            expect.any(Object),
        )
        expect(response.total).toBe(1)
        expect(response.items[0].id).toBe('task-001')
    })

    it('merges task detail and message endpoints into the page shape', async () => {
        const fetchMock = vi.fn((input: string) => {
            if (input.includes('/messages')) {
                return Promise.resolve(
                    jsonResponse({
                        code: 0,
                        msg: 'ok',
                        data: {
                            total: 1,
                            page: 1,
                            size: 200,
                            messages: [
                                {
                                    msgId: 'msg-001',
                                    status: 'SUCCESS',
                                    latestAttemptWorkerId: 'worker-us-01',
                                    latestAttemptWorkerContextId: 'ctx-us-01',
                                    latestAttemptBatchId: 'batch-001',
                                    retryCount: 0,
                                    maxRetryCount: 3,
                                    finalReason: 'BUSINESS_SUCCESS',
                                    input: { target: 'alpha' },
                                    output: { result: 'ok' },
                                    errorMessage: null,
                                },
                            ],
                        },
                    }),
                )
            }

            return Promise.resolve(
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
                        items: [{ target: 'alpha' }],
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
        })
        vi.stubGlobal('fetch', fetchMock)

        const detail = await getTaskDetailReal('task-001')

        expect(fetchMock).toHaveBeenCalledTimes(2)
        expect(detail.task.user.name).toBe('-')
        expect(detail.task.createTime).toBe('2026-04-21 09:00:00')
        expect(detail.messages[0].finalReason).toBe('BUSINESS_SUCCESS')
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
            '/backend/status/api/tasks/task-001/terminate',
            expect.objectContaining({
                method: 'POST',
            }),
        )
        expect(response.message).toBe('Task terminated')
    })
})
