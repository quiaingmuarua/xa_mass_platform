import { createTask, getTaskDetail, listTasks } from '@/api/tasks'

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
            routingCode: 'us',
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
        expect(detail.items).toEqual([{ target: 'alpha' }, { target: 'beta' }])
        expect(detail.messages).toHaveLength(2)
    })
})
