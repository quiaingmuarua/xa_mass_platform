import {createTask, getTaskDetail, invokeSyncTaskDebug, listTasks} from '@/api/tasks'

describe('tasks API facade', () => {
    it('creates a task through the mock adapter and exposes it in list and detail reads', async () => {
        const result = await createTask({
            userId: 'ops-admin',
            project: 'demoApp',
            taskName: 'Create from console test',
            sharedConfig: {
                textContent: 'hello',
            },
            inputs: [{ target: 'alpha' }, { target: 'beta' }],
            batchSize: 2,
            defaultMsgMaxRetryCount: 3,
            openEnded: false,
            maxRuntimeSeconds: 0,
        })

        expect(result.message).toBe('Task created')

        const list = await listTasks({ keyword: 'Create from console test' })
        expect(list.items).toHaveLength(1)
        expect(list.items[0].id).toBe(result.taskId)
        expect(list.items[0].status).toBe('NEW')

        const detail = await getTaskDetail(result.taskId)
        expect(detail.task.taskName).toBe('Create from console test')
        expect(detail.stateValidation.totalMessages).toBe(2)
    })

    it('submits sync debug invocations through the mock adapter', async () => {
        const result = await invokeSyncTaskDebug({
            userId: 'ops-admin',
            project: 'demoApp',
            taskName: 'worker-debug:mock.state.get',
            eventCode: 'mock.state.get',
            payloadType: 'JSON',
            sharedConfig: {
                targetWorkerId: 'worker-us-01',
            },
            inputs: [{ includeRuntime: true }],
            maxRuntimeSeconds: 60,
        })

        expect(result.taskId).toMatch(/^task-\d+$/)
        expect(result.messageId).toBe('mock-message-001')
        expect(result.synced).toBe(true)
        expect(result.timedOut).toBe(false)
        expect(result.status).toBe('SUCCESS')
    })
})
