import ElementPlus from 'element-plus'
import {flushPromises, mount} from '@vue/test-utils'
import {mockAdminUser, mockViewerUser} from '@/auth/mock-user'
import {setMockCurrentUser} from '@/auth/use-auth'
import WorkerDebugPanel from '@/components/WorkerDebugPanel.vue'

const createTask = vi.fn()

vi.mock('@/api/tasks', () => ({
    createTask,
}))

describe('WorkerDebugPanel', () => {
    const worker = {
        workerId: 'worker-us-01',
        status: 'ONLINE',
        workerGroupId: null,
        agentVersion: '1.0.0',
        supportedProjects: ['demoApp'],
        supportedEventCodes: ['mock.state.get'],
        attributes: {},
        lastHeartbeat: '2026-04-21 09:45:00',
        locked: false,
        updateTime: '2026-04-21 09:45:00',
    }

    beforeEach(() => {
        createTask.mockReset()
    })

    it('submits a targeted task instead of a direct worker message', async () => {
        setMockCurrentUser(mockAdminUser)
        createTask.mockResolvedValue({
            taskId: 'task-debug-001',
            message: 'Task created',
        })

        const wrapper = mount(WorkerDebugPanel, {
            props: {
                worker,
                projectOptions: ['demoApp'],
            },
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        const submitButton = wrapper
            .findAll('button')
            .find((item) => item.text().includes('Create targeted task'))
        expect(submitButton).toBeTruthy()

        await submitButton!.trigger('click')
        await flushPromises()

        expect(createTask).toHaveBeenCalledWith({
            userId: mockAdminUser.id,
            project: 'demoApp',
            taskName: 'worker-debug:mock.state.get',
            eventCode: 'mock.state.get',
            mode: 'SINGLE_RUN',
            payloadType: 'JSON',
            sharedConfig: {
                targetWorkerId: 'worker-us-01',
            },
            inputs: [
                {
                    includeRuntime: true,
                },
            ],
            batchSize: 1,
            defaultMsgMaxRetryCount: 0,
            openEnded: false,
            maxRuntimeSeconds: 60,
        })
        expect(wrapper.text()).toContain('task-debug-001')
    })

    it('disables task submission for users without task:create permission', async () => {
        setMockCurrentUser(mockViewerUser)

        const wrapper = mount(WorkerDebugPanel, {
            props: {
                worker,
                projectOptions: ['demoApp'],
            },
            global: {
                plugins: [ElementPlus],
            },
        })

        await flushPromises()

        expect(wrapper.text()).toContain('task:create permission is required')
        const submitButton = wrapper
            .findAll('button')
            .find((item) => item.text().includes('Create targeted task'))
        expect(submitButton?.attributes('disabled')).toBeDefined()
    })
})
