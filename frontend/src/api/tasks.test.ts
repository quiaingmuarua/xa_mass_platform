import {
    appendTaskItems,
    createTaskShell,
    getTaskDetail,
    invokeSyncTaskDebug,
    listTasks,
    sealTask,
} from '@/api/tasks'

describe('tasks API facade', () => {
    it('creates a task shell through atomic mock adapters and exposes it in list and detail reads', async () => {
        const result = await createTaskShell({
            userId: 'ops-admin',
            project: 'demoApp',
            sharedConfig: {
                textContent: 'hello',
            },
            executionSpec: {
                batchSize: 2,
                maxRuntimeSeconds: 0,
            },
        })
        await appendTaskItems(result.taskId, {
            eventCode: 'mock.state.get',
            items: [{ target: 'alpha' }, { target: 'beta' }],
        })
        await sealTask(result.taskId)

        expect(result.message).toBe('Task shell created')

        const list = await listTasks({ keyword: result.taskId })
        expect(list.items).toHaveLength(1)
        expect(list.items[0].id).toBe(result.taskId)
        expect(list.items[0].status).toBe('NEW')
        expect(list.items[0].taskName).toBe(`demoApp-${result.taskId}`)

        const detail = await getTaskDetail(result.taskId)
        expect(detail.task.taskName).toBe(`demoApp-${result.taskId}`)
        expect(detail.task.taskEligibleNumber).toBe(2)
    })

    it('submits sync debug invocations through the mock adapter', async () => {
        const result = await invokeSyncTaskDebug({
            userId: 'ops-admin',
            project: 'demoApp',
            eventCode: 'mock.state.get',
            sharedConfig: {
                targetWorkerId: 'worker-us-01',
            },
            items: [{ includeRuntime: true }],
            maxRuntimeSeconds: 60,
        })

        expect(result.taskId).toMatch(/^task-\d+$/)
        expect(result.messageId).toBe('mock-message-001')
        expect(result.synced).toBe(true)
        expect(result.timedOut).toBe(false)
        expect(result.status).toBe('SUCCESS')
    })
})
